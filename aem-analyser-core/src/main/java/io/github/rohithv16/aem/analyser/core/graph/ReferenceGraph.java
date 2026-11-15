package io.github.rohithv16.aem.analyser.core.graph;

import io.github.rohithv16.aem.analyser.core.model.graph.EdgeType;
import io.github.rohithv16.aem.analyser.core.model.graph.PolyglotEdge;
import io.github.rohithv16.aem.analyser.core.model.graph.PolyglotNode;
import io.github.rohithv16.aem.analyser.core.model.graph.edge.ConsumesPropertyEdge;
import org.jgrapht.Graph;
import org.jgrapht.graph.AsUnmodifiableGraph;
import org.jgrapht.graph.DirectedPseudograph;

import java.util.Set;

/**
 * FEAT-GRAPH-01: the thread-confined cross-boundary reference graph.
 * Only the ingestion thread mutates the underlying {@link DirectedPseudograph};
 * consumers receive an immutable view after {@link #seal()}.
 */
public final class ReferenceGraph {

    private final DirectedPseudograph<PolyglotNode, PolyglotEdge> delegate;
    private Graph<PolyglotNode, PolyglotEdge> sealedView;
    private final ReferenceGraphIndex index = new ReferenceGraphIndex();

    public ReferenceGraph() {
        this.delegate = new DirectedPseudograph<>(null, null, false);
    }

    /**
     * Ingestion thread only: registers a vertex.
     */
    public void addVertex(PolyglotNode node) {
        delegate.addVertex(node);
    }

    /**
     * Ingestion thread only: registers an edge, adding missing vertices automatically.
     */
    public void addEdge(PolyglotEdge edge) {
        if (!delegate.containsVertex(edge.getSource())) {
            delegate.addVertex(edge.getSource());
        }
        if (!delegate.containsVertex(edge.getTarget())) {
            delegate.addVertex(edge.getTarget());
        }
        delegate.addEdge(edge.getSource(), edge.getTarget(), edge);
    }

    public boolean containsEdge(PolyglotEdge edge) {
        return delegate.containsEdge(edge);
    }

    public boolean containsVertex(PolyglotNode node) {
        return delegate.containsVertex(node);
    }

    public ReferenceGraphIndex getIndex() {
        return index;
    }

    /**
     * Seals the graph: future mutations throw {@link UnsupportedOperationException}.
     */
    public Graph<PolyglotNode, PolyglotEdge> seal() {
        if (sealedView == null) {
            sealedView = new AsUnmodifiableGraph<>(delegate);
        }
        return sealedView;
    }

    public Set<PolyglotEdge> edges() {
        return delegate.edgeSet();
    }

    public Set<PolyglotNode> vertices() {
        return delegate.vertexSet();
    }

    public long countEdges(EdgeType type) {
        return delegate.edgeSet().stream().filter(e -> e.getEdgeType() == type).count();
    }

    public long inDegree(PolyglotNode node, EdgeType type) {
        return delegate.incomingEdgesOf(node).stream().filter(e -> e.getEdgeType() == type).count();
    }

    public long outDegree(PolyglotNode node, EdgeType type) {
        return delegate.outgoingEdgesOf(node).stream().filter(e -> e.getEdgeType() == type).count();
    }

    public java.util.List<PolyglotEdge> incomingEdgesOf(PolyglotNode node) {
        return new java.util.ArrayList<>(delegate.incomingEdgesOf(node));
    }

    public java.util.List<PolyglotEdge> edgesOf(PolyglotNode node) {
        return new java.util.ArrayList<>(delegate.edgesOf(node));
    }

    public java.util.List<PolyglotEdge> outgoingEdgesOfType(PolyglotNode node, EdgeType type) {
        return delegate.outgoingEdgesOf(node).stream().filter(e -> e.getEdgeType() == type).toList();
    }

    public java.util.List<ConsumesPropertyEdge> incomingConsumptions(PolyglotNode propertyMethod) {
        return incomingEdgesOf(propertyMethod).stream()
                .filter(ConsumesPropertyEdge.class::isInstance)
                .map(ConsumesPropertyEdge.class::cast)
                .toList();
    }
}
