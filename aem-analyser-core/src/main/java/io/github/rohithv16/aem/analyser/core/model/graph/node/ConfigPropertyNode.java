package io.github.rohithv16.aem.analyser.core.model.graph.node;

import io.github.rohithv16.aem.analyser.core.model.graph.DataType;
import io.github.rohithv16.aem.analyser.core.model.graph.NodeType;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;

import java.nio.file.Path;

/**
 * A single property key declared inside an OSGi configuration file.
 */
public final class ConfigPropertyNode extends AbstractPolyglotNode {

    private final String key;
    private final String rawKey;
    private final String typeHint;
    private final String rawValue;
    private final DataType inferredType;
    private final boolean isEnvPlaceholder;

    public ConfigPropertyNode(String configId,
                              Path filePath,
                              SourceLocation sourceLocation,
                              String key,
                              String rawKey,
                              String typeHint,
                              String rawValue,
                              DataType inferredType,
                              boolean isEnvPlaceholder) {
        super(configId + "#" + key, filePath, sourceLocation);
        this.key = key;
        this.rawKey = rawKey;
        this.typeHint = typeHint;
        this.rawValue = rawValue;
        this.inferredType = inferredType;
        this.isEnvPlaceholder = isEnvPlaceholder;
    }

    @Override
    public NodeType getNodeType() {
        return NodeType.CONFIG_PROPERTY;
    }

    public String getKey() {
        return key;
    }

    public String getRawKey() {
        return rawKey;
    }

    public String getTypeHint() {
        return typeHint;
    }

    public String getRawValue() {
        return rawValue;
    }

    public DataType getInferredType() {
        return inferredType;
    }

    public boolean isEnvPlaceholder() {
        return isEnvPlaceholder;
    }
}
