package io.github.rohithv16.aem.analyser.core.export;

/**
 * Supported export formats.
 */
public enum ReportFormat {
    SARIF,
    SONAR,
    CONSOLE,
    HTML,
    GITHUB,
    GITLAB,
    AZURE,
    /** Neutral machine-readable document (#36): {@code aem-analyser-report.json}. */
    JSON
}
