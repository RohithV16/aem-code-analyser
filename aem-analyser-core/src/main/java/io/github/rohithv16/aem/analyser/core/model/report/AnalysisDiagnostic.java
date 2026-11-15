package io.github.rohithv16.aem.analyser.core.model.report;

import java.nio.file.Path;

/**
 * A non-fatal problem that prevented part of the analysis from running.
 *
 * <p>Diagnostics never fail the build. They are surfaced on the console, in verbose output and as
 * SARIF tool-execution notifications so a silently shortened scan cannot look like a clean result.
 *
 * @param kind   category of the problem
 * @param path   file or directory concerned (absolute or reactor-relative)
 * @param reason short human-readable cause
 * @param line   1-based line number when known, otherwise 0
 */
public record AnalysisDiagnostic(DiagnosticKind kind, String path, String reason, int line) {

    public AnalysisDiagnostic {
        path = path == null ? "" : path;
        reason = reason == null ? "" : reason;
        line = Math.max(0, line);
    }

    public static AnalysisDiagnostic of(DiagnosticKind kind, Path path, String reason) {
        return new AnalysisDiagnostic(kind, path == null ? "" : path.toString(), reason, 0);
    }

    public static AnalysisDiagnostic of(DiagnosticKind kind, Path path, int line, String reason) {
        return new AnalysisDiagnostic(kind, path == null ? "" : path.toString(), reason, line);
    }
}
