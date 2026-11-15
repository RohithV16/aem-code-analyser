package io.github.rohithv16.aem.analyser.core.util;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisDiagnostic;
import io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Thread-safe collector for {@link AnalysisDiagnostic}s produced by parallel parsers and scanners.
 * An instance is created per analysis run; ignoring it is safe (diagnostics are simply not reported).
 */
public final class DiagnosticSink {

    private final List<AnalysisDiagnostic> diagnostics = Collections.synchronizedList(new ArrayList<>());

    public void add(DiagnosticKind kind, Path path, String reason) {
        diagnostics.add(AnalysisDiagnostic.of(kind, path, reason));
    }

    public void add(DiagnosticKind kind, Path path, int line, String reason) {
        diagnostics.add(AnalysisDiagnostic.of(kind, path, line, reason));
    }

    public void add(AnalysisDiagnostic diagnostic) {
        if (diagnostic != null) {
            diagnostics.add(diagnostic);
        }
    }

    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }

    public int size() {
        return diagnostics.size();
    }

    /**
     * @return an immutable, deterministically ordered snapshot (kind, path, line, reason)
     */
    public List<AnalysisDiagnostic> diagnostics() {
        List<AnalysisDiagnostic> snapshot;
        synchronized (diagnostics) {
            snapshot = new ArrayList<>(diagnostics);
        }
        snapshot.sort(Comparator.comparing((AnalysisDiagnostic d) -> d.kind().name())
                .thenComparing(AnalysisDiagnostic::path)
                .thenComparingInt(AnalysisDiagnostic::line)
                .thenComparing(AnalysisDiagnostic::reason));
        return List.copyOf(snapshot);
    }

    /**
     * @return a concise exception message including the root cause
     */
    public static String reasonOf(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
