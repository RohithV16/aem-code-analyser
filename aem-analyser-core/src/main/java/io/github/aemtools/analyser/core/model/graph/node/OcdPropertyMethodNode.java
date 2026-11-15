package io.github.aemtools.analyser.core.model.graph.node;

import io.github.aemtools.analyser.core.model.graph.NodeType;
import io.github.aemtools.analyser.core.model.graph.SourceLocation;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * An {@code @AttributeDefinition} method declared on an {@link OSGiOcdNode}.
 */
public final class OcdPropertyMethodNode extends AbstractPolyglotNode {

    private final String ocdFqn;
    private final String methodName;
    private final String explicitAttributeId;
    private final String normalizedOsgiPropName;
    private final String returnType;
    private final List<String> defaultValue;
    private final boolean required;

    public OcdPropertyMethodNode(String ocdFqn,
                                 String methodName,
                                 String explicitAttributeId,
                                 String normalizedOsgiPropName,
                                 String returnType,
                                 List<String> defaultValue,
                                 boolean required,
                                 Path filePath,
                                 SourceLocation sourceLocation) {
        super(ocdFqn + "#" + methodName, filePath, sourceLocation);
        this.ocdFqn = ocdFqn;
        this.methodName = methodName;
        this.explicitAttributeId = explicitAttributeId;
        this.normalizedOsgiPropName = normalizedOsgiPropName;
        this.returnType = returnType;
        this.defaultValue = List.copyOf(defaultValue);
        this.required = required;
    }

    @Override
    public NodeType getNodeType() {
        return NodeType.OCD_PROPERTY_METHOD;
    }

    public String getOcdFqn() {
        return ocdFqn;
    }

    public String getMethodName() {
        return methodName;
    }

    public Optional<String> getExplicitAttributeId() {
        return Optional.ofNullable(explicitAttributeId);
    }

    public String getNormalizedOsgiPropName() {
        return normalizedOsgiPropName;
    }

    public String getReturnType() {
        return returnType;
    }

    public List<String> getDefaultValue() {
        return defaultValue;
    }

    public boolean isRequired() {
        return required;
    }
}
