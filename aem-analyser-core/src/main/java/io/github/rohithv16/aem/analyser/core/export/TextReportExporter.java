package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.io.Writer;

/**
 * Issue #53: one compiler-style line per finding, for line-oriented consumers.
 *
 * <p>Emits exactly {@code path:line:column: E AEM-DEAD-004 message}, one physical line per finding
 * and nothing else. That is the shape vim's {@code:cfgetfile} / {@code:make}, Emacs
 * {@code compilation-mode}, VS Code's {@code $gcc} problem matcher, flycheck and review-diff tools
 * already parse — no extension, no JSON, no dashboard.
 *
 * <p>The severity letter is fixed to {@code E}/{@code W}/{@code I} so a matcher can map it to an
 * error/warning/info class; the message has its newlines folded to spaces so one finding can never
 * spill onto a second line and shift every following entry.
 */
public final class TextReportExporter implements ReportExporter {

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.TEXT;
    }

    @Override
    public void export(AnalysisReport report, Writer writer) throws ExportException {
        try {
            for (Violation violation : report.violations()) {
                writer.write(format(violation));
                writer.write('\n');
            }
            writer.flush();
        } catch (IOException e) {
            throw new ExportException("Failed to write text report: " + e.getMessage(), e);
        }
    }

    /** The single line for one finding, without a trailing newline. */
    static String format(Violation violation) {
        int line = violation.region() == null ? 1 : violation.region().startLine();
        int column = violation.region() == null ? 1 : violation.region().startColumn();
        return violation.filePath()
                + ":" + line
                + ":" + column
                + ": " + severityInitial(violation)
                + " " + violation.ruleId()
                + " " + oneLine(violation.message());
    }

    /** Stable single-letter severity: the first letter of the severity name. */
    static char severityInitial(Violation violation) {
        return violation.severity().name().charAt(0);
    }

    /** Folds every whitespace run (including newlines and tabs) into one space. */
    static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        String flattened = value.replaceAll("\\s+", " ").strip();
        return flattened.isEmpty() ? "" : flattened;
    }
}
