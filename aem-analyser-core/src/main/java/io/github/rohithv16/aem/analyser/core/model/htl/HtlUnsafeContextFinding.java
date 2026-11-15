package io.github.rohithv16.aem.analyser.core.model.htl;

import java.nio.file.Path;

/**
 * Finding representing an unsafe HTL expression that bypasses XSS protection
 * via {@code @ context='unsafe'}.
 */
public record HtlUnsafeContextFinding(
        Path filePath,
        int line,
        int column,
        String snippet
) {}
