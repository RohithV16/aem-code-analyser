package io.github.rohithv16.aem.analyser.cli;

import java.nio.file.Path;
import java.util.Locale;

/**
 * One {@code --diagnose RULE:PATH:LINE} selector. The path may contain ':' only after the rule id;
 * the line is everything after the last colon, so a path cannot be mistaken for the rule or line.
 */
record DiagnoseTarget(String ruleId, String path, int line) {

    static DiagnoseTarget parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("--diagnose needs RULE:PATH:LINE, e.g. "
                    + "\"AEM-DEAD-004:ui.apps/card.html:42\".");
        }
        String value = raw.strip();
        int first = value.indexOf(':');
        int last = value.lastIndexOf(':');
        if (first <= 0 || last <= first || last == value.length() - 1) {
            throw new IllegalArgumentException("Invalid --diagnose '" + raw
                    + "'. Expected RULE:PATH:LINE, e.g. \"AEM-DEAD-004:ui.apps/card.html:42\".");
        }
        String ruleId = value.substring(0, first).strip().toUpperCase(Locale.ROOT);
        String path = value.substring(first + 1, last).strip();
        int line;
        try {
            line = Integer.parseInt(value.substring(last + 1).strip());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("Invalid line in --diagnose '" + raw
                    + "'. The last field must be a 1-based line number.");
        }
        if (path.isEmpty()) {
            throw new IllegalArgumentException("Invalid --diagnose '" + raw + "'. The path is empty.");
        }
        if (line < 1) {
            throw new IllegalArgumentException("Invalid line " + line + " in --diagnose; lines are 1-based.");
        }
        return new DiagnoseTarget(ruleId, normalizePath(path), line);
    }

    /** Reactor-relative, '/'-separated path; an absolute path is relativised by the caller. */
    static String normalizePath(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    /** Relativises an absolute selector path against the project root, or returns it unchanged. */
    DiagnoseTarget relativeTo(Path root) {
        Path selected = Path.of(path);
        if (!selected.isAbsolute()) {
            return this;
        }
        Path absolute = selected.normalize();
        Path project = root.toAbsolutePath().normalize();
        if (absolute.startsWith(project)) {
            return new DiagnoseTarget(ruleId, normalizePath(project.relativize(absolute).toString()), line);
        }
        return this;
    }
}
