package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * FEAT-REP-03: rich ANSI console formatter with severity colors, coordinates,
 * root-cause hints and remediation steps.
 */
public final class ConsoleReportFormatter implements ReportExporter {

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_RED = "\u001B[31;1m";
    private static final String ANSI_YELLOW = "\u001B[33;1m";
    private static final String ANSI_CYAN = "\u001B[36;1m";
    private static final String ANSI_GREEN = "\u001B[32;1m";
    private static final String ANSI_BOLD = "\u001B[1m";

    private final boolean colorEnabled;

    public ConsoleReportFormatter() {
        this.colorEnabled = true;
    }

    public ConsoleReportFormatter(boolean colorEnabled) {
        this.colorEnabled = colorEnabled;
    }

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.CONSOLE;
    }

    @Override
    public void export(AnalysisReport report, Writer writer) throws ExportException {
        StringBuilder sb = new StringBuilder(4096);
        String line = "=".repeat(80);
        sb.append(line).append('\n');
        sb.append(" AEM CODE ANALYSER - POLYGLOT DEAD CODE REPORT\n");
        sb.append(line).append('\n');
        sb.append(" Project Root: ").append(report.projectRoot()).append('\n');
        report.scanStatistics().forEach((key, value) ->
                sb.append(' ').append(capitalize(key)).append(": ").append(value).append('\n'));
        long errors = report.countBySeverity(Severity.ERROR);
        long warnings = report.countBySeverity(Severity.WARNING);
        long infos = report.countBySeverity(Severity.INFO);
        sb.append(" Total Violations: ").append(report.violations().size())
                .append(" (").append(errors).append(" Errors, ")
                .append(warnings).append(" Warnings, ")
                .append(infos).append(" Infos)\n");
        sb.append(" Execution Time: ").append(String.format("%.3f", report.durationMillis() / 1000.0))
                .append(" s\n");
        sb.append(line).append('\n').append('\n');

        for (Violation violation : report.violations()) {
            String color = color(violation.severity());
            sb.append(color).append('[').append(violation.severity()).append(']').append(ANSI_RESET);
            sb.append(' ').append(color).append(violation.ruleId()).append(ANSI_RESET);
            sb.append(": ").append(ruleTitle(violation.ruleId())).append('\n');
            if (violation.subtype() != null) {
                sb.append("  Subtype: ").append(violation.subtype()).append('\n');
            }
            sb.append("  File: ").append(violation.filePath())
                    .append(':').append(violation.region().startLine())
                    .append(':').append(violation.region().startColumn()).append('\n');
            violation.targetPidOpt().ifPresent(pid ->
                    sb.append("  Target PID: ").append(pid).append('\n'));
            sb.append("  ").append(violation.message()).append('\n');
            violation.remediationOpt().ifPresent(r ->
                    sb.append("  Remediation: ").append(r).append('\n'));
            if (!violation.relatedLocations().isEmpty()) {
                sb.append("  Related:\n");
                violation.relatedLocations().forEach(related ->
                        sb.append("    - ").append(related.filePath())
                                .append(':').append(related.region().startLine())
                                .append("  (").append(related.message())
                                .append(")\n"));
            }
            sb.append('\n');
        }

        if (report.violations().isEmpty()) {
            sb.append(colorEnabled ? ANSI_GREEN : "");
            sb.append("BUILD SUCCESS: no cross-boundary dead code detected.");
            sb.append(colorEnabled ? ANSI_RESET : "");
            sb.append('\n').append(line).append('\n');
        } else if (errors > 0) {
            sb.append(colorEnabled ? ANSI_RED + ANSI_BOLD : "");
            sb.append("BUILD FAILURE: ").append(errors).append(" critical cross-boundary error")
                    .append(errors == 1 ? "" : "s").append(" detected.");
            sb.append(colorEnabled ? ANSI_RESET : "");
            sb.append('\n').append(line).append('\n');
        } else {
            sb.append("BUILD SUCCESS WITH WARNINGS: ").append(warnings).append(" warning(s), ")
                    .append(infos).append(" info message(s).").append('\n').append(line).append('\n');
        }

        try {
            writer.write(sb.toString());
            writer.flush();
        } catch (IOException e) {
            throw new ExportException("Failed to write console report: " + e.getMessage(), e);
        }
    }

    private String ruleTitle(String ruleId) {
        return switch (RuleId.fromId(ruleId)) {
            case AEM_DEAD_001 -> "Orphaned OSGi Configuration";
            case AEM_DEAD_002A -> "Ghost Configuration Property";
            case AEM_DEAD_002B -> "Unconsumed OCD Property";
            case AEM_DEAD_003A -> "Unused OSGi Service";
            case AEM_DEAD_003B -> "Unused Sling Model";
            case AEM_DEAD_003C -> "Inactive Delayed Component";
        };
    }

    private String color(Severity severity) {
        if (!colorEnabled) {
            return "";
        }
        return switch (severity) {
            case ERROR -> ANSI_RED;
            case WARNING -> ANSI_YELLOW;
            case INFO -> ANSI_CYAN;
        };
    }

    private String capitalize(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        List<String> camel = List.of("cloudConfigs", "osgiConfigs", "slingModels");
        if (camel.contains(key)) {
            return Character.toUpperCase(key.charAt(0)) + key.substring(1);
        }
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }
}
