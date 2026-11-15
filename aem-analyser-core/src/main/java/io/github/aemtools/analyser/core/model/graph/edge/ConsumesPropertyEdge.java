package io.github.aemtools.analyser.core.model.graph.edge;

import io.github.aemtools.analyser.core.model.graph.EdgeType;
import io.github.aemtools.analyser.core.model.graph.PolyglotEdge;
import io.github.aemtools.analyser.core.model.graph.PolyglotNode;
import io.github.aemtools.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.aemtools.analyser.core.model.graph.node.OcdPropertyMethodNode;
import io.github.aemtools.analyser.core.model.graph.SourceLocation;

/**
 * Property consumption edge: an OSGi component consumes an {@code @AttributeDefinition} method.
 */
public record ConsumesPropertyEdge(
        OSGiComponentNode sourceComponent,
        OcdPropertyMethodNode targetPropertyMethod,
        SourceLocation callSiteLocation,
        InvocationType invocationType,
        String enclosingMethodName
) implements PolyglotEdge {

    public enum InvocationType {
        DIRECT_METHOD_CALL,
        FIELD_MEMBER_ACCESS,
        CONSTRUCTOR_INJECTION,
        COMPONENT_PROPERTY_TYPE
    }

    @Override
    public EdgeType getEdgeType() {
        return EdgeType.CONSUMES_PROPERTY;
    }

    @Override
    public PolyglotNode getSource() {
        return sourceComponent;
    }

    @Override
    public PolyglotNode getTarget() {
        return targetPropertyMethod;
    }

    @Override
    public String getDescription() {
        return "Component " + sourceComponent.getId() + " consumes property method "
                + targetPropertyMethod.getId() + " at " + callSiteLocation;
    }
}
