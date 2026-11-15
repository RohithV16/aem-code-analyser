package io.github.rohithv16.aem.analyser.core.parser.java;

import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;

import java.nio.file.Path;

/**
 * Record of a call to deprecated administrative APIs (AMSCORE-553).
 */
public record AdministrativeCall(
        String methodName,
        String enclosingClassFqn,
        Path filePath,
        SourceLocation location
) {}
