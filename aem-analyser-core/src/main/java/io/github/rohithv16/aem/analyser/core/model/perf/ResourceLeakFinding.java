package io.github.rohithv16.aem.analyser.core.model.perf;

import java.nio.file.Path;

/**
 * Finding representing an unclosed ResourceResolver or JCR Session.
 */
public record ResourceLeakFinding(
        Path filePath,
        int line,
        int column,
        String variableName,
        String callName,
        LeakKind kind
) {
    public enum LeakKind {
        RESOURCE_RESOLVER,
        JCR_SESSION
    }
}
