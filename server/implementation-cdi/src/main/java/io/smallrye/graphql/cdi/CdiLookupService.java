package io.smallrye.graphql.cdi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.CDI;

import io.smallrye.graphql.spi.LookupService;

/**
 * Lookup service that gets the beans via CDI
 *
 * @author Phillip Kruger (phillip.kruger@redhat.com)
 */
public class CdiLookupService implements LookupService {

    private final Map<String, Instance<?>> cachedInstances = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "CDI";
    }

    @Override
    public Class<?> getClass(Class<?> declaringClass) {
        Object declaringObject = getInstance(declaringClass);
        return declaringObject.getClass();
    }

    @Override
    public Object getInstance(Class<?> declaringClass) {
        final String className = declaringClass.getName();
        if (!cachedInstances.containsKey(className)) {
            cachedInstances.put(className, CDI.current().select(declaringClass));
        }
        return cachedInstances.get(className).get();
    }
}
