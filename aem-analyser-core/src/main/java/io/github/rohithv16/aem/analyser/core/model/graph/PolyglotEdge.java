package io.github.rohithv16.aem.analyser.core.model.graph;

/**
 * Base contract of every edge in the cross-boundary reference graph.
 */
public interface PolyglotEdge {
    EdgeType getEdgeType();

    PolyglotNode getSource();

    PolyglotNode getTarget();

    String getDescription();
}
