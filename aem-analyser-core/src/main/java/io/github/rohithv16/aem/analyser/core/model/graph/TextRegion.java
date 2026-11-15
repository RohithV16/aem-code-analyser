package io.github.rohithv16.aem.analyser.core.model.graph;

/**
 * A rectangular source text region with 1-based coordinates.
 */
public record TextRegion(int startLine, int startColumn, int endLine, int endColumn) {

    public static TextRegion forWholeFile() {
        return new TextRegion(1, 1, 1, 2);
    }

    public static TextRegion forLine(int line) {
        int l = Math.max(1, line);
        return new TextRegion(l, 1, l, 2);
    }

    public TextRegion {
        if (startLine < 1) {
            startLine = 1;
        }
        if (startColumn < 1) {
            startColumn = 1;
        }
        if (endLine < startLine) {
            endLine = startLine;
        }
        if (endColumn < startColumn) {
            endColumn = startColumn;
        }
    }
}
