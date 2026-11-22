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
    JSON,
    /** Compiler-style one line per finding (#53): {@code aem-analyser.txt}. */
    TEXT,
    /** JUnit XML for CI test tabs (#43): {@code aem-analyser-junit.xml}. */
    JUNIT
}
