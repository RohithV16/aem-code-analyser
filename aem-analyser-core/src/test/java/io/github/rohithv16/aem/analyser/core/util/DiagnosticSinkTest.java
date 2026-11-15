package io.github.rohithv16.aem.analyser.core.util;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisDiagnostic;
import io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticSinkTest {

    @Test
    void returnsDeterministicallySortedSnapshot() {
        DiagnosticSink sink = new DiagnosticSink();
        sink.add(DiagnosticKind.UNREADABLE_TREE, Path.of("/b"), "unreadable");
        sink.add(DiagnosticKind.JAVA_PARSE_ERROR, Path.of("/a"), "syntax");
        sink.add(DiagnosticKind.JAVA_PARSE_ERROR, Path.of("/a"), 7, "syntax line");

        List<AnalysisDiagnostic> diagnostics = sink.diagnostics();

        assertEquals(3, diagnostics.size());
        assertEquals(DiagnosticKind.JAVA_PARSE_ERROR, diagnostics.get(0).kind());
        assertEquals(DiagnosticKind.JAVA_PARSE_ERROR, diagnostics.get(1).kind());
        assertEquals(7, diagnostics.get(1).line());
        assertEquals(DiagnosticKind.UNREADABLE_TREE, diagnostics.get(2).kind());
        assertTrue(sink.size() >= 3);
    }

    @Test
    void reasonOfUnwrapsRootCause() {
        RuntimeException root = new IllegalStateException("root cause");
        RuntimeException wrapped = new RuntimeException("wrapper", root);

        assertEquals("root cause", DiagnosticSink.reasonOf(wrapped));
    }
}
