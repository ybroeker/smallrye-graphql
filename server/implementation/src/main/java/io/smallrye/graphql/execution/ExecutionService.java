package io.smallrye.graphql.execution;

import static io.smallrye.graphql.SmallRyeGraphQLServerLogging.log;

import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import javax.json.*;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;
import javax.json.spi.JsonProvider;

import io.smallrye.graphql.api.Scalar;
import org.dataloader.BatchLoaderWithContext;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderOptions;
import org.dataloader.DataLoaderRegistry;

import graphql.ExecutionInput;
import graphql.ExecutionInput.Builder;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLContext;
import graphql.GraphQLError;
import graphql.execution.ExecutionId;
import graphql.schema.GraphQLSchema;
import io.smallrye.graphql.api.Context;
import io.smallrye.graphql.bootstrap.Config;
import io.smallrye.graphql.bootstrap.DataFetcherFactory;
import io.smallrye.graphql.execution.context.SmallRyeBatchLoaderContextProvider;
import io.smallrye.graphql.execution.context.SmallRyeContext;
import io.smallrye.graphql.execution.datafetcher.helper.BatchLoaderHelper;
import io.smallrye.graphql.execution.error.ExceptionHandler;
import io.smallrye.graphql.execution.error.ExecutionErrorsService;
import io.smallrye.graphql.execution.event.EventEmitter;
import io.smallrye.graphql.schema.model.Operation;

/**
 * Executing the GraphQL request
 * 
 * @author Phillip Kruger (phillip.kruger@redhat.com)
 */
public class ExecutionService {

    private static final JsonProvider jsonProvider = JsonProvider.provider();

    private static final JsonBuilderFactory jsonObjectFactory = Json.createBuilderFactory(null);
    private static final JsonReaderFactory jsonReaderFactory = Json.createReaderFactory(null);
    private static final Jsonb jsonB = JsonbBuilder.create(new JsonbConfig()
            .withNullValues(Boolean.TRUE)
            .withFormatting(Boolean.TRUE));

    private final String executionIdPrefix;
    private final AtomicLong executionId = new AtomicLong();

    private final ExecutionErrorsService errorsService = new ExecutionErrorsService();

    private final Config config;

    private final GraphQLSchema graphQLSchema;

    private final BatchLoaderHelper batchLoaderHelper = new BatchLoaderHelper();
    private final DataFetcherFactory dataFetcherFactory;
    private final List<Operation> batchOperations;

    private final EventEmitter eventEmitter;

    private GraphQL graphQL;

    public ExecutionService(Config config, GraphQLSchema graphQLSchema, List<Operation> batchOperations) {
        this.config = config;
        this.graphQLSchema = graphQLSchema;
        this.dataFetcherFactory = new DataFetcherFactory(config);
        this.batchOperations = batchOperations;
        this.eventEmitter = EventEmitter.getInstance(config);
        // use schema's hash as prefix to differentiate between multiple apps
        this.executionIdPrefix = Integer.toString(Objects.hashCode(graphQLSchema));
    }

    public JsonObject execute(JsonObject jsonInput) {
        SmallRyeContext context = new SmallRyeContext(jsonInput);

        // ExecutionId
        ExecutionId finalExecutionId = ExecutionId.from(executionIdPrefix + executionId.getAndIncrement());

        try {
            String query = context.getQuery();

            if (config.logPayload()) {
                log.payloadIn(query);
            }

            GraphQL g = getGraphQL();
            if (g != null) {
                // Query
                Builder executionBuilder = ExecutionInput.newExecutionInput()
                        .query(query)
                        .executionId(finalExecutionId);

                // Variables
                context.getVariables().ifPresent(executionBuilder::variables);

                // Operation name
                context.getOperationName().ifPresent(executionBuilder::operationName);

                // Context
                executionBuilder.context(toGraphQLContext(context));

                // DataLoaders

                if (batchOperations != null && !batchOperations.isEmpty()) {
                    DataLoaderRegistry dataLoaderRegistry = getDataLoaderRegistry(batchOperations);
                    executionBuilder.dataLoaderRegistry(dataLoaderRegistry);
                }

                ExecutionInput executionInput = executionBuilder.build();

                // Update context with execution data
                context = context.withDataFromExecution(executionInput);
                ((GraphQLContext) executionInput.getContext()).put("context", context);

                // Notify before
                eventEmitter.fireBeforeExecute(context);
                // Execute
                ExecutionResult executionResult = g.execute(executionInput);
                // Notify after
                eventEmitter.fireAfterExecute(context);

                JsonObjectBuilder returnObjectBuilder = jsonObjectFactory.createObjectBuilder();

                // Errors
                returnObjectBuilder = addErrorsToResponse(returnObjectBuilder, executionResult);
                // Data
                returnObjectBuilder = addDataToResponse(returnObjectBuilder, executionResult);

                JsonObject jsonResponse = returnObjectBuilder.build();

                if (config.logPayload()) {
                    log.payloadOut(jsonResponse.toString());
                }

                return jsonResponse;
            } else {
                log.noGraphQLMethodsFound();
                return null;
            }
        } catch (Throwable t) {
            eventEmitter.fireOnExecuteError(finalExecutionId.toString(), t);
            throw t; // TODO: can I remove that?
        }
    }

    private <K, T> DataLoaderRegistry getDataLoaderRegistry(List<Operation> operations) {
        DataLoaderRegistry dataLoaderRegistry = new DataLoaderRegistry();
        for (Operation operation : operations) {
            BatchLoaderWithContext<K, T> batchLoader = dataFetcherFactory.getSourceBatchLoader(operation);
            SmallRyeBatchLoaderContextProvider ctxProvider = new SmallRyeBatchLoaderContextProvider();
            DataLoaderOptions options = DataLoaderOptions.newOptions()
                    .setBatchLoaderContextProvider(ctxProvider);
            DataLoader<K, T> dataLoader = DataLoader.newDataLoader(batchLoader, options);
            ctxProvider.setDataLoader(dataLoader);
            dataLoaderRegistry.register(batchLoaderHelper.getName(operation), dataLoader);
        }
        return dataLoaderRegistry;
    }

    private GraphQLContext toGraphQLContext(Context context) {
        GraphQLContext.Builder builder = GraphQLContext.newContext();
        builder = builder.of("context", context);
        return builder.build();
    }

    private JsonObjectBuilder addDataToResponse(JsonObjectBuilder returnObjectBuilder, ExecutionResult executionResult) {
        Object pojoData = executionResult.getData();
        return addDataToResponse(returnObjectBuilder, pojoData);
    }

    private JsonObjectBuilder addDataToResponse(JsonObjectBuilder returnObjectBuilder, Object pojoData) {
        if (pojoData != null) {
            JsonValue data = toJsonValue(pojoData);
            return returnObjectBuilder.add(DATA, data);
        } else {
            return returnObjectBuilder.addNull(DATA);
        }
    }

    private JsonObjectBuilder addErrorsToResponse(JsonObjectBuilder returnObjectBuilder, ExecutionResult executionResult) {
        List<GraphQLError> errors = executionResult.getErrors();
        if (errors != null) {
            JsonArray jsonArray = errorsService.toJsonErrors(errors);
            if (!jsonArray.isEmpty()) {
                returnObjectBuilder = returnObjectBuilder.add(ERRORS, jsonArray);
            }
            return returnObjectBuilder;
        } else {
            return returnObjectBuilder;
        }

    }

    private JsonValue toJsonValue(Object pojo) {

        final JsonValue jsonValue;
        if (pojo instanceof Map) {
            JsonObjectBuilder jsonObjectBuilder = jsonObjectFactory.createObjectBuilder();
            Map<String, Object> map = (Map<String, Object>) pojo;
            for (final Map.Entry<String, Object> stringObjectEntry : map.entrySet()) {
                jsonObjectBuilder.add(stringObjectEntry.getKey(), toJsonValue(stringObjectEntry.getValue()));
            }
            jsonValue = jsonObjectBuilder.build();
        } else if (pojo instanceof Collection) {
            Collection<Object> map = ((Collection<Object>) pojo);
            JsonArrayBuilder builder = jsonObjectFactory.createArrayBuilder();
            for (final Object o : map) {
                builder.add(toJsonValue(o));
            }
            jsonValue = builder.build();
        } else if (pojo instanceof Boolean) {
            if (((Boolean) pojo)) {
                jsonValue = JsonValue.TRUE;
            } else {
                jsonValue = JsonValue.FALSE;
            }
        } else if (pojo instanceof String) {
            jsonValue = jsonProvider.createValue(((String) pojo));
        } else if (pojo instanceof Double){
            jsonValue = jsonProvider.createValue(((Double) pojo));
        } else if (pojo instanceof Long){
            jsonValue = jsonProvider.createValue(((Long) pojo));
        } else if (pojo instanceof Integer){
            jsonValue = jsonProvider.createValue(((Integer) pojo));
        } else if (pojo instanceof BigDecimal){
            jsonValue = jsonProvider.createValue(((BigDecimal) pojo));
        } else if (pojo instanceof BigInteger){
            jsonValue = jsonProvider.createValue(((BigInteger) pojo));
        }else {
            String json = jsonB.toJson(pojo);
            try (StringReader sr = new StringReader(json); JsonReader reader = jsonReaderFactory.createReader(sr)) {
                jsonValue = reader.readValue();
            }
        }

        return jsonValue;
    }

    private GraphQL getGraphQL() {
        if (this.graphQL == null) {
            ExceptionHandler exceptionHandler = new ExceptionHandler(config);
            if (graphQLSchema != null) {
                QueryCache queryCache = new QueryCache();

                GraphQL.Builder graphqlBuilder = GraphQL.newGraphQL(graphQLSchema);

                graphqlBuilder = graphqlBuilder.defaultDataFetcherExceptionHandler(exceptionHandler);
                graphqlBuilder = graphqlBuilder.instrumentation(queryCache);
                graphqlBuilder = graphqlBuilder.preparsedDocumentProvider(queryCache);

                // Allow custom extension
                graphqlBuilder = eventEmitter.fireBeforeGraphQLBuild(graphqlBuilder);

                this.graphQL = graphqlBuilder.build();
            } else {
                log.noGraphQLMethodsFound();
            }
        }
        return this.graphQL;

    }

    private static final String DATA = "data";
    private static final String ERRORS = "errors";
}
