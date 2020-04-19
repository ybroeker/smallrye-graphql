package io.smallrye.graphql.execution.resolver;

import java.util.Set;

import graphql.TypeResolutionEnvironment;
import graphql.schema.GraphQLObjectType;
import graphql.schema.TypeResolver;
import io.smallrye.graphql.schema.model.Reference;

public class UnionResolver implements TypeResolver {

    private Set<Reference> types;

    public UnionResolver(final Set<Reference> types) {

        this.types = types;
    }

    @Override
    public GraphQLObjectType getType(final TypeResolutionEnvironment env) {
        final Object object = env.getObject();

        for (final Reference type : types) {
            if (object.getClass().getName().equals(type.getClassName())) {
                return env.getSchema().getObjectType(type.getName());
            }
        }
        return null;
    }
}
