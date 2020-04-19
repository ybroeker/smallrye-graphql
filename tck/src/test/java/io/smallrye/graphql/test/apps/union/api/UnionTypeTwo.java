package io.smallrye.graphql.test.apps.union.api;

public class UnionTypeTwo implements SomeUnion {

    Integer value = 2;

    public Integer getValue() {
        return value;
    }

    public void setValue(final Integer value) {
        this.value = value;
    }
}
