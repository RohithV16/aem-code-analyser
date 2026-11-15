package io.github.aemtools.analyser.core.model.graph.node;

import io.github.aemtools.analyser.core.model.graph.NodeType;
import io.github.aemtools.analyser.core.model.graph.PolyglotNode;
import io.github.aemtools.analyser.core.model.graph.SourceLocation;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable base implementation of {@link PolyglotNode}.
 */
public abstract class AbstractPolyglotNode implements PolyglotNode {

    private final String id;
    private final Path filePath;
    private final SourceLocation sourceLocation;

    protected AbstractPolyglotNode(String id, Path filePath, SourceLocation sourceLocation) {
        this.id = Objects.requireNonNull(id, "id");
        this.filePath = filePath == null ? null : filePath.toAbsolutePath().normalize();
        this.sourceLocation = sourceLocation;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Path getFilePath() {
        return filePath;
    }

    @Override
    public SourceLocation getSourceLocation() {
        return sourceLocation;
    }

    @Override
    public abstract NodeType getNodeType();

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PolyglotNode other)) {
            return false;
        }
        return id.equals(other.getId()) && getNodeType() == other.getNodeType();
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, getNodeType());
    }

    @Override
    public String toString() {
        return getNodeType() + "{" + id + "}";
    }
}
