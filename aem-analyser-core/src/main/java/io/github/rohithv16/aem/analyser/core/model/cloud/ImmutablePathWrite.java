package io.github.rohithv16.aem.analyser.core.model.cloud;

import java.nio.file.Path;

/**
 * A runtime mutation of a repository path that AEM as a Cloud Service treats as immutable.
 *
 * @param path       the JCR path the code writes to, as written in the source (`/apps/...`)
 * @param operation  the mutating operation observed on the resolved node or resource
 * @param filePath   the Java source file
 * @param line       1-based line of the path-taking call
 * @param column     1-based column of the path-taking call
 */
public record ImmutablePathWrite(String path,
                                 String operation,
                                 Path filePath,
                                 int line,
                                 int column) {

    public ImmutablePathWrite {
        if (line < 1) {
            line = 1;
        }
        if (column < 1) {
            column = 1;
        }
    }
}
