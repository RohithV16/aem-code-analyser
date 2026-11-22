package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.GateVerdict;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.io.Writer;
import java.util.Comparator;
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
        String div = "-".repeat(80);

        // Header
        sb.append(line).append('\n');
        sb.append(" AEM CODE ANALYSER - CROSS-BOUNDARY QUALITY REPORT\n");
        sb.append(line).append('\n');
        sb.append(" Project Root: ").append(report.projectRoot()).append('\n');
        sb.append(line).append('\n').append('\n');

        // 1. Violations first (sorted: ERROR -> WARNING -> INFO)
        List<Violation> sortedViolations = report.violations().stream()
                .sorted(Comparator.comparingInt((Violation v) -> switch (v.severity()) {
                    case ERROR -> 1;
                    case WARNING -> 2;
                    case INFO -> 3;
                }).thenComparing(Violation::filePath)
                        .thenComparingInt(v -> v.region().startLine()))
                .toList();

        if (sortedViolations.isEmpty()) {
            sb.append(" No cross-boundary violations detected.\n\n");
        } else {
            String reset = colorEnabled ? ANSI_RESET : "";
            for (Violation violation : sortedViolations) {
                String color = color(violation.severity());
                sb.append(color).append('[').append(violation.severity()).append(']').append(reset);
                sb.append(' ').append(color).append(violation.ruleId()).append(reset);
                sb.append(": ").append(ruleTitle(violation.ruleId())).append('\n');
                if (violation.subtype() != null) {
                    sb.append("  Subtype: ").append(violation.subtype()).append('\n');
                }
                sb.append("  File: ").append(violation.filePath())
                        .append(':').append(violation.region().startLine())
                        .append(':').append(violation.region().startColumn()).append('\n');
                violation.targetPidOpt().ifPresent(pid ->
                        sb.append("  Target: ").append(pid).append('\n'));
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
        }

        // 2. Scan Summary & Metrics at the end
        sb.append(line).append('\n');
        sb.append(" SCAN SUMMARY & METRICS\n");
        sb.append(line).append('\n');

        if (!report.scanStatistics().isEmpty()) {
            sb.append(" Scan Statistics:\n");
            report.scanStatistics().forEach((key, value) ->
                    sb.append("  ").append(capitalize(key)).append(": ").append(value).append('\n'));
        }

        if (!report.diagnostics().isEmpty()) {
            sb.append(" Diagnostics: ").append(report.diagnostics().size())
                    .append(" file(s) skipped or degraded (run with --verbose for details)\n");
        }

        // Code Metrics & Coverage section
        sb.append(div).append('\n');
        sb.append(" [Code Metrics]\n");
        var metrics = report.codeMetrics();
        sb.append(String.format("  Java Source Files:  %,d classes (%,d lines of code)\n",
                metrics.totalJavaFiles(), metrics.totalJavaLines()));
        sb.append(String.format("  Duplicate Code:     %,d duplicate lines (%.1f%% duplication) across %,d blocks\n",
                metrics.duplicatedLines(), metrics.duplicationPercent(), metrics.totalMethodBlocks()));
        if (!metrics.duplicateClasses().isEmpty()) {
            sb.append("                      Classes with duplicate code:\n");
            for (var dupClass : metrics.duplicateClasses()) {
                sb.append(String.format("                       - %s: %,d dup lines (%.1f%% of class)\n",
                        dupClass.className(), dupClass.duplicatedLines(), dupClass.duplicationPercent()));
            }
        }

        var cov = report.coverageReport();
        if (cov != null && cov.present()) {
            sb.append(String.format("  Test Code Coverage: %.1f%% Lines (%,d / %,d) | %.1f%% Branches (%,d / %,d)\n",
                    cov.lineCoveragePercent(), cov.coveredLines(), cov.totalLines(),
                    cov.branchCoveragePercent(), cov.coveredBranches(), cov.totalBranches()));
            if (cov.sourcePath() != null) {
                sb.append("                      [from ").append(cov.sourcePath()).append("]\n");
            }
        } else {
            sb.append("  Test Code Coverage: Not detected (run 'mvn test' first to generate JaCoCo report)\n");
        }
        sb.append(div).append('\n');

        long errors = report.countBySeverity(Severity.ERROR);
        long warnings = report.countBySeverity(Severity.WARNING);
        long infos = report.countBySeverity(Severity.INFO);
        sb.append(" Total Violations: ").append(report.violations().size())
                .append(" (").append(errors).append(errors == 1 ? " Error, " : " Errors, ")
                .append(warnings).append(warnings == 1 ? " Warning, " : " Warnings, ")
                .append(infos).append(infos == 1 ? " Info)\n" : " Infos)\n");
        sb.append(" Execution Time: ").append(String.format("%.3f", report.durationMillis() / 1000.0))
                .append(" s\n");
        sb.append(line).append('\n');

        // The verdict comes from the effective gate (fail-on level, warning flags, quality
        // thresholds), not from the error count: --fail-on NONE must never read as GATE FAILED.
        GateVerdict verdict = report.gateVerdict();
        String verdictColor = verdictColor(verdict.status());
        sb.append(verdictColor).append(verdict.render()).append(colorEnabled ? ANSI_RESET : "");
        sb.append('\n').append(line).append('\n');

        try {
            writer.write(sb.toString());
            writer.flush();
        } catch (IOException e) {
            throw new ExportException("Failed to write console report: " + e.getMessage(), e);
        }
    }

    private String ruleTitle(String ruleId) {
        if (ruleId != null && ruleId.startsWith("java:S")) {
            return "SonarJava rule " + ruleId;
        }
        try {
            return switch (RuleId.fromId(ruleId)) {
                case AEM_DEAD_001 -> "Orphaned OSGi Configuration";
                case AEM_DEAD_002A -> "Ghost Configuration Property";
                case AEM_DEAD_002B -> "Unconsumed OCD Property";
                case AEM_DEAD_003A -> "Unused OSGi Service";
                case AEM_DEAD_003B -> "Unused Sling Model";
                case AEM_DEAD_003C -> "Inactive Delayed Component";
                case AEM_DUP_001 -> "Duplicate Servlet Endpoint";
                case AEM_DUP_002 -> "Duplicate OSGi Configuration";
                case AEM_DUP_003 -> "Duplicate Sling Model";
                case AEM_DUP_010 -> "Duplicate Code Block";
                case AEM_QUAL_001 -> "Path-Bound Sling Servlet Registration (CQBP-75)";
                case AEM_QUAL_002 -> "Implementation of @ProviderType Product API (CQBP-84)";
                case AEM_QUAL_003 -> "Whiteboard Sling Scheduler Usage (AMSCORE-554)";
                case AEM_QUAL_004 -> "Unsafe Mutable State in OSGi Component (CQBP-ThreadSafety)";
                case AEM_QUAL_005 -> "Deprecated Administrative API Usage (AMSCORE-553)";
                case AEM_DEAD_004 -> "Broken HTL Reference";
                case AEM_COMP_001 -> "Deprecated Classic UI Dialog (dialog.xml)";
                case AEM_PERF_001 -> "Unclosed ResourceResolver (Session Leak Risk)";
                case AEM_PERF_002 -> "Unclosed JCR Session Leak";
                case AEM_PERF_003 -> "Oak Query Traversal Risk";
                case AEM_CLOUD_001 -> "Plaintext Secret in Configuration (AEMaaCS)";
                case AEM_CLOUD_002 -> "Deprecated JSP Script (AEMaaCS)";
                case AEM_SEC_001 -> "Unsafe HTL XSS Context Bypass";
                case AEM_CLOUD_003 -> "Unmapped Subservice / Service User (AEMaaCS)";
                case AEM_CLOUD_004 -> "Deprecated AEM API Usage (AEMaaCS)";
                case AEM_CLOUD_005 -> "Invalid Runmode Folder (AEMaaCS)";
                case AEM_QUAL_006 -> "Resolver/Session Stored in Component Field";
                case AEM_DEAD_005 -> "Orphaned Clientlib Category";
                case AEM_SEC_002 -> "Open Redirect / SSRF";
                case AEM_PERF_004 -> "Unbounded JCR/Oak Query";
                case AEM_COMP_002 -> "Resource Type Names No Component";
                case AEM_QUAL_008 -> "Unsatisfied Mandatory @Reference";
                case AEM_DEAD_007 -> "Broken HTL Include";
            };
        } catch (IllegalArgumentException e) {
            return ruleId;
        }
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

    /** A failed gate is red and bold; an unenforced or warning-only run is yellow; a pass is green. */
    private String verdictColor(GateVerdict.Status status) {
        if (!colorEnabled) {
            return "";
        }
        return switch (status) {
            case FAILED -> ANSI_RED + ANSI_BOLD;
            case NOT_ENFORCED, PASSED_WITH_WARNINGS -> ANSI_YELLOW + ANSI_BOLD;
            case PASSED -> ANSI_GREEN;
        };
    }

    private String capitalize(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        List<String> camel = List.of("cloudConfigs", "osgiConfigs", "slingModels", "codeBlocks", "cqComponents", "htlUsages");
        if (camel.contains(key)) {
            return Character.toUpperCase(key.charAt(0)) + key.substring(1);
        }
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }
}
