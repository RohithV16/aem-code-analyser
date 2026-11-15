package io.github.aemtools.analyser.core.model.graph;

import java.nio.file.Path;

/**
 * Universal 1-based source coordinates of a symbol within a file.
 *
 * @param projectRelativePath path relative to the analysed project root, '/'-separated conceptually
 * @param line                1-based line number
 * @param column              1-based column number
 */
public record SourceLocation(Path projectRelativePath, int line, int column) {

    public static SourceLocation wholeFile(Path projectRelativePath) {
        return new SourceLocation(projectRelativePath, 1, 1);
    }

    public SourceLocation {
        if (line < 1) {
            line = 1;
        }
        if (column < 1) {
            column = 1;
        }
    }
}
