package io.github.rohithv16.aem.analyser.core.model.graph;

import java.nio.file.Path;

/**
 * Base contract of every node in the cross-boundary reference graph.
 */
public interface PolyglotNode {
    String getId();

    NodeType getNodeType();

    Path getFilePath();

    SourceLocation getSourceLocation();
}
