package io.smallrye.graphql.schema.model;

import java.util.LinkedHashSet;
import java.util.Set;

public class UnionType extends Reference {

    private final String description;

    private final Set<Reference> types = new LinkedHashSet<>();

    public UnionType(final String javaName, final String name, final String description) {
        super(javaName, name, ReferenceType.UNION);
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public Set<Reference> getTypes() {
        return types;
    }

    public void addTypes(final Reference interfaceRef) {
        types.add(interfaceRef);
    }
}
