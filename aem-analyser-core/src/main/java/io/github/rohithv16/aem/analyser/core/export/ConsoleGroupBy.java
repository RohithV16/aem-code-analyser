package io.github.rohithv16.aem.analyser.core.export;

import java.util.Locale;

/**
 * How the console lists findings: flat, or grouped so a reader can work file by file or rule by rule.
 *
 * <p>The HTML dashboard has offered this grouping client side for a while; the terminal had no
 * equivalent, which left a developer reading a 250-line flat list.
 */
public enum ConsoleGroupBy {

    /** One block per finding, severity first (the historical layout). */
    NONE,
    /** One header per file, most findings first. */
    FILE,
    /** One header per rule, most findings first. */
    RULE;

    /**
     * Parses the {@code --group-by} value case-insensitively.
     *
     * @throws IllegalArgumentException naming the accepted values, for a usage error (exit 2)
     */
    public static ConsoleGroupBy parse(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown --group-by value '" + value
                    + "'. Supported values: FILE, RULE, NONE.");
        }
    }
}
