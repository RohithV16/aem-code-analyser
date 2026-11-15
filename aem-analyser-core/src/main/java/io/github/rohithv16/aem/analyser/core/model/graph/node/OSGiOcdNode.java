package io.github.rohithv16.aem.analyser.core.model.graph.node;

import io.github.rohithv16.aem.analyser.core.model.graph.NodeType;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An OSGi Metatype {@code @ObjectClassDefinition} interface.
 */
public final class OSGiOcdNode extends JavaClassNode {

    private final String pid;
    private final String metatypeName;
    private final Map<String, OcdPropertyMethodNode> propertyMethods;

    public OSGiOcdNode(String fqn,
                       String pid,
                       String metatypeName,
                       Set<ModifierKind> modifiers,
                       Path filePath,
                       SourceLocation sourceLocation) {
        super(fqn, Kind.INTERFACE, modifiers, null, Set.of(), filePath, sourceLocation);
        this.pid = pid;
        this.metatypeName = metatypeName;
        this.propertyMethods = new LinkedHashMap<>();
    }

    @Override
    public NodeType getNodeType() {
        return NodeType.OSGI_OCD;
    }

    public String getOcdFqn() {
        return getFqn();
    }

    public String getPid() {
        return pid;
    }

    public Optional<String> getMetatypeName() {
        return Optional.ofNullable(metatypeName);
    }

    public Map<String, OcdPropertyMethodNode> getPropertyMethods() {
        return propertyMethods;
    }

    public void addPropertyMethod(OcdPropertyMethodNode method) {
        propertyMethods.put(method.getMethodName(), method);
    }

    public Optional<OcdPropertyMethodNode> findMethodByName(String methodName) {
        return Optional.ofNullable(propertyMethods.get(methodName));
    }
}
