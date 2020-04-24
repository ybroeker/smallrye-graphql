package io.smallrye.graphql.schema.creator.type;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.MethodInfo;
import org.jboss.logging.Logger;

import io.smallrye.graphql.schema.Annotations;
import io.smallrye.graphql.schema.ScanningContext;
import io.smallrye.graphql.schema.creator.FieldCreator;
import io.smallrye.graphql.schema.creator.ReferenceCreator;
import io.smallrye.graphql.schema.helper.DescriptionHelper;
import io.smallrye.graphql.schema.helper.Direction;
import io.smallrye.graphql.schema.helper.MethodHelper;
import io.smallrye.graphql.schema.helper.TypeNameHelper;
import io.smallrye.graphql.schema.model.InterfaceType;
import io.smallrye.graphql.schema.model.Reference;
import io.smallrye.graphql.schema.model.ReferenceType;
import io.smallrye.graphql.schema.model.UnionType;

/**
 * This creates an interface object.
 * 
 * The interface object has fields that might reference other types that should still be created.
 * It might also implement some interfaces that should be created.
 * 
 * @author Phillip Kruger (phillip.kruger@redhat.com)
 */
public class UnionCreator implements Creator<UnionType> {
    private static final Logger LOG = Logger.getLogger(InputTypeCreator.class.getName());

    private final FieldCreator fieldCreator;

    private final ReferenceCreator referenceCreator;

    public UnionCreator(final FieldCreator fieldCreator, final ReferenceCreator referenceCreator) {
        this.fieldCreator = fieldCreator;
        this.referenceCreator = referenceCreator;
    }

    @Override
    public UnionType create(ClassInfo classInfo) {
        LOG.debug("Creating Union from " + classInfo.name().toString());

        Annotations annotations = Annotations.getAnnotationsForClass(classInfo);

        // Name
        String name = TypeNameHelper.getAnyTypeName(ReferenceType.INTERFACE, classInfo, annotations);

        // Description
        String description = DescriptionHelper.getDescriptionForType(annotations).orElse(null);

        UnionType interfaceType = new UnionType(classInfo.name().toString(), name, description);

        // Interfaces
        addSubtypes(interfaceType, classInfo);

        return interfaceType;
    }

    private void addFields(InterfaceType interfaceType, ClassInfo classInfo) {
        // Fields
        List<MethodInfo> allMethods = new ArrayList<>();

        // Find all methods up the tree
        for (ClassInfo c = classInfo; c != null; c = ScanningContext.getIndex().getClassByName(c.superName())) {
            if (!c.toString().startsWith(JAVA_DOT)) { // Not java interfaces (like Serializable)
                allMethods.addAll(c.methods());
            }
        }

        for (MethodInfo methodInfo : allMethods) {
            if (MethodHelper.isPropertyMethod(Direction.OUT, methodInfo.name())) {
                fieldCreator.createFieldForInterface(methodInfo)
                        .ifPresent(interfaceType::addField);
            }
        }
    }

    private void addSubtypes(UnionType unionType, ClassInfo classInfo) {
        Collection<ClassInfo> knownDirectImplementors = ScanningContext.getIndex()
                .getAllKnownImplementors(classInfo.name());
        for (ClassInfo impl : knownDirectImplementors) {
            Reference ref = referenceCreator.createReference(Direction.OUT, impl);
            unionType.addTypes(ref);
        }

    }

    private static final String JAVA_DOT = "java.";
}
