package io.github.rohithv16.aem.analyser.core.model.perf;

import java.nio.file.Path;

/**
 * Finding representing a risky unconstrained JCR/Oak repository query.
 */
public record QueryTraversalFinding(
        Path filePath,
        int line,
        int column,
        String queryString,
        String queryLanguage
) {}
