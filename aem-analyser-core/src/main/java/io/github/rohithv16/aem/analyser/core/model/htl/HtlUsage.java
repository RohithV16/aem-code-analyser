package io.github.rohithv16.aem.analyser.core.model.htl;

import java.nio.file.Path;

/**
 * Represents a data-sly-use reference in an HTL template.
 */
public record HtlUsage(
        Path filePath,
        int line,
        int column,
        String identifier,
        String target
) {
    public boolean isJavaClass() {
        if (target == null || target.isBlank()) {
            return false;
        }
        String clean = target.strip();
        if (clean.contains("/") || clean.endsWith(".html") || clean.endsWith(".js") || clean.endsWith(".jsp")) {
            return false;
        }
        return clean.contains(".") || Character.isUpperCase(clean.charAt(0));
    }

    public String javaClassName() {
        if (!isJavaClass()) {
            return null;
        }
        String clean = target.strip();
        if (clean.endsWith(".class")) {
            clean = clean.substring(0, clean.length() - 6);
        }
        return clean;
    }
}
