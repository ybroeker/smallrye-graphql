package io.smallrye.graphql.schema.helper;

import java.util.Optional;

import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

import io.smallrye.graphql.schema.Annotations;
import io.smallrye.graphql.schema.ScanningContext;
import io.smallrye.graphql.schema.model.Field;
import io.smallrye.graphql.schema.model.Mapping;
import io.smallrye.graphql.schema.model.Reference;
import io.smallrye.graphql.schema.model.ReferenceType;
import io.smallrye.graphql.schema.model.Scalars;

/**
 * Helping with mapping of scalars
 * 
 * @author Phillip Kruger (phillip.kruger@redhat.com)
 */
public class MappingHelper {

    private MappingHelper() {
    }

    public static boolean shouldCreateTypeInSchema(Annotations annotations) {
        return !hasAnyMapping(annotations);
    }

    /**
     * Get the mapping for a certain field.
     * 
     * @param annotations the annotations
     * @return Potentially a MappingInfo model
     */
    public static Optional<Mapping> getMapping(Field field, Annotations annotations) {
        return getMapping(field.getReference(), annotations);
    }

    /**
     * Get the mapping for a certain reference.
     * 
     * @param annotations the annotations
     * @return Potentially a MappingInfo model
     */
    public static Optional<Mapping> getMapping(Reference r, Annotations annotations) {
        Type type = getMapTo(annotations);
        if (type != null) {
            String scalarName = getScalarName(type);
            Reference reference = Scalars.getScalar(scalarName);
            Mapping mappingInfo = new Mapping(reference);
            // Check the way to create this.
            String className = r.getClassName();
            if (!r.getType().equals(ReferenceType.SCALAR)) { // mapping to scalar stays on default NONE
                ClassInfo classInfo = ScanningContext.getIndex().getClassByName(DotName.createSimple(className));
                if (classInfo != null) {
                    // Get Parameter type
                    Type parameter = Type.create(DotName.createSimple(reference.getClassName()), Type.Kind.CLASS);

                    // Check if we can use a constructor
                    MethodInfo constructor = classInfo.method(CONTRUCTOR_METHOD_NAME, parameter);
                    if (constructor != null) {
                        mappingInfo.setCreate(Mapping.Create.CONSTRUCTOR);
                    } else {
                        // Check if we can use setValue
                        MethodInfo setValueMethod = classInfo.method("setValue", parameter);
                        if (setValueMethod != null) {
                            mappingInfo.setCreate(Mapping.Create.SET_VALUE);
                        } else {
                            // Check if we can use static fromXXXXX
                            String staticFromMethodName = "from" + scalarName;
                            MethodInfo staticFromMethod = classInfo.method(staticFromMethodName, parameter);
                            if (staticFromMethod != null) {
                                mappingInfo.setCreate(Mapping.Create.STATIC_FROM);
                            }
                        }
                    }

                }
            }
            return Optional.of(mappingInfo);
        } else {
            // TODO: Support other than Scalar mapping 
        }
        return Optional.empty();
    }

    private static boolean hasAnyMapping(Annotations annotations) {
        Type type = getMapTo(annotations);
        return type != null;
    }

    private static String getScalarName(Type type) {
        String className = type.name().withoutPackagePrefix();
        if (className.contains("$")) {
            return className.substring(className.lastIndexOf("$") + 1);
        }
        return className;
    }

    private static Type getMapTo(Annotations annotations) {
        if (annotations != null && annotations.containsOneOfTheseAnnotations(Annotations.TO_SCALAR)) {
            AnnotationValue annotationValue = annotations.getAnnotationValue(Annotations.TO_SCALAR);
            if (annotationValue != null) {
                return annotationValue.asClass();
            }
        }
        return null;
    }

    private static final String CONTRUCTOR_METHOD_NAME = "<init>";
}
