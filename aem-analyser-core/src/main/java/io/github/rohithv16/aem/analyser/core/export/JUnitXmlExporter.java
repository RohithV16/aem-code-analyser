package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.io.Writer;

/**
 * Issue #43: findings rendered as a JUnit XML report, so Jenkins, GitLab and Azure DevOps show
 * them on the test tab instead of a single red log line.
 *
 * <p>One {@code <testcase>} per finding ({@code classname} = rule id, {@code name} =
 * {@code file:line}); ERROR and WARNING carry a {@code <failure>}, INFO becomes {@code <skipped/>}.
 * The document contains no clock, duration or hash-order data, so two runs over an unchanged tree
 * are byte-identical — a CI artefact diff stays empty and a report can be signed.
 */
public final class JUnitXmlExporter implements ReportExporter {

    static final String SUITE_NAME = "aem-code-analyser";

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.JUNIT;
    }

    @Override
    public void export(AnalysisReport report, Writer writer) throws ExportException {
        long failures = 0;
        long skipped = 0;
        for (Violation violation : report.violations()) {
            if (violation.severity() == Severity.INFO) {
                skipped++;
            } else {
                failures++;
            }
        }

        StringBuilder xml = new StringBuilder(256 + report.violations().size() * 256);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"").append(SUITE_NAME).append('"')
                .append(" tests=\"").append(report.violations().size()).append('"')
                .append(" failures=\"").append(failures).append('"')
                .append(" errors=\"0\"")
                .append(" skipped=\"").append(skipped).append('"')
                .append(" time=\"0\">\n");

        for (Violation violation : report.violations()) {
            testCase(xml, violation);
        }

        xml.append("</testsuite>\n");
        try {
            writer.write(xml.toString());
            writer.flush();
        } catch (IOException e) {
            throw new ExportException("Failed to write JUnit XML report: " + e.getMessage(), e);
        }
    }

    private void testCase(StringBuilder xml, Violation violation) {
        int line = violation.region() == null ? 1 : violation.region().startLine();
        xml.append("  <testcase classname=\"").append(attribute(violation.ruleId())).append('"')
                .append(" name=\"").append(attribute(violation.filePath() + ":" + line)).append('"')
                .append(" time=\"0\">\n");

        if (violation.severity() == Severity.INFO) {
            xml.append("    <skipped/>\n");
        } else {
            String message = oneLine(violation.message());
            StringBuilder body = new StringBuilder(violation.message() == null ? "" : violation.message());
            violation.remediationOpt().ifPresent(remediation -> body
                    .append("\nRemediation: ").append(remediation));
            xml.append("    <failure type=\"").append(violation.severity().name()).append('"')
                    .append(" message=\"").append(attribute(message)).append("\">")
                    .append(text(body.toString()))
                    .append("</failure>\n");
        }

        // The rule id and OSGi PID used to live only in console output; keep them machine-readable
        // without inventing a non-JUnit attribute.
        StringBuilder systemOut = new StringBuilder("rule=").append(violation.ruleId());
        violation.targetPidOpt().ifPresent(pid -> systemOut.append(" targetPid=").append(pid));
        xml.append("    <system-out>").append(text(systemOut.toString())).append("</system-out>\n");
        xml.append("  </testcase>\n");
    }

    /** Escapes XML text content. */
    static String text(String value) {
        return escape(value, false);
    }

    /** Escapes a double-quoted attribute value; newlines are folded so attributes stay single-line. */
    static String attribute(String value) {
        return escape(oneLine(value), true);
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String escape(String value, boolean attribute) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append(attribute ? "&quot;" : "\"");
                case '\'' -> escaped.append(attribute ? "&apos;" : "'");
                default -> {
                    // XML 1.0 forbids most control characters outright; drop them rather than
                    // producing a document xmllint rejects.
                    if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
