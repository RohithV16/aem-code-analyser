package io.github.rohithv16.aem.analyser.core.parser.java;

import java.nio.file.Path;

/**
 * Snapshot of a method or non-trivial code block for duplicate code analysis (AEM-DUP-010).
 */
public record CodeBlockSnapshot(
        Path filePath,
        String classFqn,
        String methodName,
        int startLine,
        int endLine,
        int lineCount,
        int statementCount,
        String normalizedHash,
        String preview) {
}
