package io.smallrye.graphql.test.apps.union.api;

public class UnionTypeOne implements SomeUnion {

    String name = "one";

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

}
