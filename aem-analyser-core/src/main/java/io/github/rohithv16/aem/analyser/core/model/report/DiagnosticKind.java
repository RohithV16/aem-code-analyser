package io.github.rohithv16.aem.analyser.core.model.report;

/**
 * Categories of non-fatal problems encountered during analysis.
 */
public enum DiagnosticKind {
    /** A Java source file could not be parsed. */
    JAVA_PARSE_ERROR,
    /** An OSGi configuration file could not be parsed. */
    CONFIG_PARSE_ERROR,
    /** A directory or file could not be read. */
    UNREADABLE_TREE,
    /** A class file inside a dependency JAR could not be indexed. */
    MALFORMED_CLASS,
    /** A dependency JAR or JAR root could not be indexed. */
    DEPENDENCY_INDEX,
    /** A legacy factory PID could not be disambiguated. */
    AMBIGUOUS_PID,
    /** A configuration contained duplicate keys. */
    DUPLICATE_KEY,
    /** A file exceeded the configured maximum analysis size and was skipped. */
    FILE_TOO_LARGE,
    /** The configured analysis timeout elapsed before all files were processed. */
    ANALYSIS_TIMEOUT
}
