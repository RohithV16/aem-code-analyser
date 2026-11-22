package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.GateVerdict;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FEAT-REP-03: rich ANSI console formatter with severity colors, coordinates,
 * root-cause hints and remediation steps.
 *
 * <p>The report leads with the answer: the gate verdict, the severity counts, the files with the
 * most findings and the paths of the reports that were written. A reader or a CI log viewer must
 * not have to scroll past every finding to learn whether the build passed.</p>
 */
public final class ConsoleReportFormatter implements ReportExporter {

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_RED = "\u001B[31;1m";
    private static final String ANSI_YELLOW = "\u001B[33;1m";
    private static final String ANSI_CYAN = "\u001B[36;1m";
    private static final String ANSI_GREEN = "\u001B[32;1m";
    private static final String ANSI_BOLD = "\u001B[1m";

    /** Files named on the hotspot line; three is what fits one terminal line without wrapping. */
    private static final int HOTSPOT_LIMIT = 3;

    private static final String BULLET = "\u00b7";

    private final boolean colorEnabled;
    private final ConsoleGroupBy groupBy;
    private final List<String> reportPaths;
    private final boolean reproducible;

    public ConsoleReportFormatter() {
        this(true);
    }

    public ConsoleReportFormatter(boolean colorEnabled) {
        this(colorEnabled, ConsoleGroupBy.NONE, List.of(), false);
    }

    /**
     * @param colorEnabled ANSI colors
     * @param groupBy      how findings are listed
     * @param reportPaths  report files written by this run, shown in the triage block
     * @param reproducible a fixed report clock is installed: the wall-clock duration is omitted so
     *                     the same tree yields identical bytes
     */
    public ConsoleReportFormatter(boolean colorEnabled, ConsoleGroupBy groupBy, List<String> reportPaths,
                                  boolean reproducible) {
        this.colorEnabled = colorEnabled;
        this.groupBy = groupBy == null ? ConsoleGroupBy.NONE : groupBy;
        this.reportPaths = reportPaths == null ? List.of() : List.copyOf(reportPaths);
        this.reproducible = reproducible;
    }

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.CONSOLE;
    }

    @Override
    public void export(AnalysisReport report, Writer writer) throws ExportException {
        StringBuilder sb = new StringBuilder(8192);
        String line = "=".repeat(80);
        String div = "-".repeat(80);
        String reset = colorEnabled ? ANSI_RESET : "";

        GateVerdict verdict = report.gateVerdict();
        List<Violation> sortedViolations = sortViolations(report.violations());
        long errors = report.countBySeverity(Severity.ERROR);
        long warnings = report.countBySeverity(Severity.WARNING);
        long infos = report.countBySeverity(Severity.INFO);

        // 1. The answer first: verdict, counts, hotspots and written reports. Everything a reader
        // needs to decide whether to look at the findings at all is above the findings.
        sb.append(line).append('\n');
        sb.append(verdictColor(verdict.status())).append(" AEM CODE ANALYSER \u2014 ")
                .append(verdict.headline()).append(reset).append('\n');
        sb.append(" ").append(verdict.reason()).append('\n');
        sb.append(" Total Violations: ").append(sortedViolations.size()).append(" (")
                .append(errors).append(errors == 1 ? " Error, " : " Errors, ")
                .append(warnings).append(warnings == 1 ? " Warning, " : " Warnings, ")
                .append(infos).append(infos == 1 ? " Info)" : " Infos)");
        appendRunFacts(sb, report);
        sb.append('\n');
        appendHotspots(sb, sortedViolations);
        if (!reportPaths.isEmpty()) {
            sb.append(" Reports: ").append(String.join(", ", reportPaths)).append('\n');
        }
        sb.append(" Project Root: ").append(report.projectRoot()).append('\n');
        sb.append(line).append('\n').append('\n');

        // 2. Findings, flat or grouped as requested.
        sb.append(" FINDINGS (").append(sortedViolations.size()).append(')');
        switch (groupBy) {
            case FILE -> sb.append(" \u2014 grouped by file");
            case RULE -> sb.append(" \u2014 grouped by rule");
            case NONE -> {
                // no marker: the flat list is the default
            }
        }
        sb.append('\n').append(div).append('\n');
        if (sortedViolations.isEmpty()) {
            sb.append(" No cross-boundary violations detected.\n\n");
        } else if (groupBy == ConsoleGroupBy.NONE) {
            appendDetailedFindings(sb, sortedViolations, reset);
        } else {
            appendGroupedFindings(sb, sortedViolations, reset);
        }

        // 3. Scan Summary & Metrics at the end: context, not the answer.
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

        // The closing line repeats the verdict so the last line of a log is still the answer; the
        // counts live in the triage block and are not repeated here.
        sb.append(verdictColor(verdict.status())).append(verdict.render()).append(reset);
        sb.append('\n').append(line).append('\n');

        try {
            writer.write(sb.toString());
            writer.flush();
        } catch (IOException e) {
            throw new ExportException("Failed to write console report: " + e.getMessage(), e);
        }
    }

    /** Severity first, then coordinates: the same order the machine formats (SARIF) emit. */
    private static List<Violation> sortViolations(List<Violation> violations) {
        return violations.stream()
                .sorted(Comparator.comparingInt((Violation v) -> switch (v.severity()) {
                    case ERROR -> 1;
                    case WARNING -> 2;
                    case INFO -> 3;
                }).thenComparing(Violation::filePath)
                        .thenComparingInt(v -> v.region().startLine()))
                .toList();
    }

    /**
     * " · 6.4 s · 11 modules". The duration is a wall-clock measurement, so a reproducible run
     * (fixed report clock) omits it: the same tree has to yield identical bytes.
     */
    private void appendRunFacts(StringBuilder sb, AnalysisReport report) {
        if (!reproducible) {
            sb.append(String.format(Locale.ROOT, " %s %.1f s", BULLET, report.durationMillis() / 1000.0));
        }
        Integer modules = report.scanStatistics().get("modules");
        if (modules != null) {
            sb.append(' ').append(BULLET).append(' ').append(modules)
                    .append(modules == 1 ? " module" : " modules");
        }
    }

    /** The files with the most findings, so the first place to look is on line 5, not inferred. */
    private void appendHotspots(StringBuilder sb, List<Violation> violations) {
        if (violations.isEmpty()) {
            return;
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Violation violation : violations) {
            counts.merge(violation.filePath(), 1L, Long::sum);
        }
        Comparator<Map.Entry<String, Long>> byCount = Map.Entry.<String, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey());
        List<Map.Entry<String, Long>> hotspots = counts.entrySet().stream()
                .sorted(byCount)
                .limit(HOTSPOT_LIMIT)
                .toList();
        sb.append(" Hotspots: ");
        for (int i = 0; i < hotspots.size(); i++) {
            if (i > 0) {
                sb.append("  ");
            }
            sb.append(hotspots.get(i).getKey()).append(" (").append(hotspots.get(i).getValue()).append(')');
        }
        sb.append('\n');
    }

    /** The full block: severity, rule, location, message, remediation, related locations. */
    private void appendDetailedFindings(StringBuilder sb, List<Violation> violations, String reset) {
        for (Violation violation : violations) {
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

    /**
     * One line per finding under a group header, so the same findings fit a screen: the file (or
     * rule) is stated once per group instead of on every six-line block.
     */
    private void appendGroupedFindings(StringBuilder sb, List<Violation> violations, String reset) {
        boolean byFile = groupBy == ConsoleGroupBy.FILE;
        Map<String, List<Violation>> groups = new LinkedHashMap<>();
        for (Violation violation : violations) {
            String key = byFile ? violation.filePath() : violation.ruleId();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(violation);
        }
        List<Map.Entry<String, List<Violation>>> ordered = groups.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<Violation>>>comparingInt(e -> -e.getValue().size())
                        .thenComparing(Map.Entry::getKey))
                .toList();

        for (Map.Entry<String, List<Violation>> group : ordered) {
            sb.append(colorEnabled ? ANSI_BOLD : "").append("  ").append(group.getKey())
                    .append(" (").append(group.getValue().size()).append(')').append(reset).append('\n');
            for (Violation violation : group.getValue()) {
                sb.append("    ").append(color(violation.severity()))
                        .append('[').append(violation.severity()).append(']').append(reset).append(' ');
                if (byFile) {
                    // The file is the header: keep the line, then the rule and message.
                    sb.append(violation.ruleId()).append(':').append(violation.region().startLine());
                } else {
                    sb.append(violation.filePath()).append(':').append(violation.region().startLine());
                }
                sb.append("  ").append(violation.message()).append('\n');
            }
        }
        sb.append('\n');
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
                case AEM_CLOUD_006 -> "Broken Dispatcher Configuration (AEMaaCS)";
                case AEM_CLOUD_007 -> "Overlapping Content-Package Filter Root (AEMaaCS)";
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
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }
}
