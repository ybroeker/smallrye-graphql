package io.smallrye.graphql.test.apps.union.api;

import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
public class TestApi {

    @Query
    public SomeUnion someUnion() {
        return new UnionTypeOne();
    }

}
