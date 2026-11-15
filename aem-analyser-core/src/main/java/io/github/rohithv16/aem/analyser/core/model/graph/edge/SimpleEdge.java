package io.github.rohithv16.aem.analyser.core.model.graph.edge;

import io.github.rohithv16.aem.analyser.core.model.graph.EdgeType;
import io.github.rohithv16.aem.analyser.core.model.graph.PolyglotEdge;
import io.github.rohithv16.aem.analyser.core.model.graph.PolyglotNode;

import java.util.Objects;

/**
 * Generic strongly-typed edge for all non-{@code CONSUMES_PROPERTY} relation types.
 */
public final class SimpleEdge implements PolyglotEdge {

    private final PolyglotNode source;
    private final PolyglotNode target;
    private final EdgeType type;
    private final String description;

    public SimpleEdge(PolyglotNode source, PolyglotNode target, EdgeType type, String description) {
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        this.type = Objects.requireNonNull(type, "type");
        this.description = description == null ? type.name() : description;
    }

    @Override
    public EdgeType getEdgeType() {
        return type;
    }

    @Override
    public PolyglotNode getSource() {
        return source;
    }

    @Override
    public PolyglotNode getTarget() {
        return target;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PolyglotEdge other)) {
            return false;
        }
        return type == other.getEdgeType()
                && source.getId().equals(other.getSource().getId())
                && source.getNodeType() == other.getSource().getNodeType()
                && target.getId().equals(other.getTarget().getId())
                && target.getNodeType() == other.getTarget().getNodeType();
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, source.getId(), source.getNodeType(), target.getId(), target.getNodeType());
    }

    @Override
    public String toString() {
        return type + "[" + source.getId() + " -> " + target.getId() + "]";
    }
}
