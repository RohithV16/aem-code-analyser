package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.history.RunSummary;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.CodeMetrics;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates an executive-ready, standalone, offline interactive HTML dashboard.
 * Designed for air-gapped CI/CD artifacts, stakeholder reviews, and local developer exploration.
 */
public final class HtmlReportExporter implements ReportExporter {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private final java.time.Clock clock;
    private final int maxViolations;
    private final List<RunSummary> history;

    public HtmlReportExporter() {
        this(java.time.Clock.systemDefaultZone(), 5000, List.of());
    }

    public HtmlReportExporter(java.time.Clock clock) {
        this(clock, 5000, List.of());
    }

    public HtmlReportExporter(java.time.Clock clock, int maxViolations) {
        this(clock, maxViolations, List.of());
    }

    public HtmlReportExporter(java.time.Clock clock, int maxViolations, List<RunSummary> history) {
        this.clock = clock == null ? java.time.Clock.systemDefaultZone() : clock;
        this.maxViolations = Math.max(1, maxViolations);
        this.history = List.copyOf(history == null ? List.of() : history);
    }

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.HTML;
    }

    @Override
    public void export(AnalysisReport report, Writer writer) throws ExportException {
        try {
            writer.write(generateHtml(report));
            writer.flush();
        } catch (IOException e) {
            throw new ExportException("Failed to generate HTML report: " + e.getMessage(), e);
        }
    }

    public String generateHtml(AnalysisReport report) {
        StringBuilder sb = new StringBuilder(32768);

        // Baseline-accepted findings are rendered in the Run Health tab, not mixed into the active
        // violation list, so totals and the quality-gate badge reflect what still needs fixing.
        List<Violation> acceptedViolations = report.violations().stream()
                .filter(v -> "SUPPRESSED_BY_BASELINE".equals(v.subtype()))
                .toList();
        List<Violation> activeViolations = report.violations().stream()
                .filter(v -> !"SUPPRESSED_BY_BASELINE".equals(v.subtype()))
                .toList();

        long errors = countBySeverity(activeViolations, Severity.ERROR);
        long warnings = countBySeverity(activeViolations, Severity.WARNING);
        long infos = countBySeverity(activeViolations, Severity.INFO);
        long totalViolations = activeViolations.size();
        int diagnosticsCount = report.diagnostics().size();

        String statusBadgeClass = errors > 0 ? "badge-danger"
                : (!report.diagnostics().isEmpty() || warnings > 0) ? "badge-warning" : "badge-success";
        String statusText = errors > 0 ? "QUALITY GATE FAILED (" + errors + " Errors)"
                : !report.diagnostics().isEmpty()
                        ? "PASSED WITH " + diagnosticsCount + " DIAGNOSTIC(S)"
                        : warnings > 0 ? "PASSED WITH WARNINGS" : "QUALITY GATE PASSED";

        List<Violation> sortedViolations = activeViolations.stream()
                .sorted(Comparator.comparingInt((Violation v) -> switch (v.severity()) {
                    case ERROR -> 1;
                    case WARNING -> 2;
                    case INFO -> 3;
                }).thenComparing(Violation::filePath)
                        .thenComparingInt(v -> v.region().startLine()))
                .toList();

        Map<String, Long> categoryCounts = activeViolations.stream()
                .collect(Collectors.groupingBy(v -> getRuleCategory(v.ruleId()), Collectors.counting()));

        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("<title>AEM Code Analyser — Enterprise Quality Dashboard</title>\n");
        sb.append("<style>\n");
        sb.append("""
                :root {
                    --bg-primary: #f8fafc;
                    --bg-card: #ffffff;
                    --text-primary: #0f172a;
                    --text-secondary: #475569;
                    --border: #e2e8f0;
                    --accent: #2563eb;
                    --accent-light: #eff6ff;
                    --danger: #dc2626;
                    --danger-bg: #fef2f2;
                    --danger-border: #fecaca;
                    --warning: #d97706;
                    --warning-bg: #fffbeb;
                    --warning-border: #fde68a;
                    --info: #0284c7;
                    --info-bg: #f0f9ff;
                    --info-border: #bae6fd;
                    --success: #16a34a;
                    --success-bg: #f0fdf4;
                    --success-border: #bbf7d0;
                }
                * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
                body { background: var(--bg-primary); color: var(--text-primary); padding: 2rem; line-height: 1.5; }
                .container { max-width: 1400px; margin: 0 auto; }
                header { display: flex; justify-content: space-between; align-items: center; background: var(--bg-card); padding: 1.5rem 2rem; border-radius: 12px; border: 1px solid var(--border); box-shadow: 0 1px 3px rgba(0,0,0,0.05); margin-bottom: 1.5rem; }
                .header-title h1 { font-size: 1.5rem; font-weight: 700; color: #1e293b; display: flex; align-items: center; gap: 0.5rem; }
                .header-title p { color: var(--text-secondary); font-size: 0.875rem; margin-top: 0.25rem; }
                .status-badge { padding: 0.5rem 1rem; border-radius: 9999px; font-weight: 700; font-size: 0.875rem; text-transform: uppercase; letter-spacing: 0.05em; }
                .badge-danger { background: var(--danger-bg); color: var(--danger); border: 1px solid var(--danger-border); }
                .badge-warning { background: var(--warning-bg); color: var(--warning); border: 1px solid var(--warning-border); }
                .badge-success { background: var(--success-bg); color: var(--success); border: 1px solid var(--success-border); }
                
                .kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 1rem; margin-bottom: 1.5rem; }
                .kpi-card { background: var(--bg-card); padding: 1.25rem; border-radius: 10px; border: 1px solid var(--border); box-shadow: 0 1px 2px rgba(0,0,0,0.04); }
                .kpi-card .label { font-size: 0.75rem; font-weight: 600; text-transform: uppercase; color: var(--text-secondary); letter-spacing: 0.05em; }
                .kpi-card .value { font-size: 1.75rem; font-weight: 700; margin-top: 0.25rem; }
                .kpi-card.err .value { color: var(--danger); }
                .kpi-card.warn .value { color: var(--warning); }
                .kpi-card.info .value { color: var(--info); }
                .kpi-card.cov .value { color: #0d9488; }
                .kpi-card.dup .value { color: #7c3aed; }
                .kpi-card.accepted .value { color: #0d9488; }

                .nav-tabs { display: flex; gap: 0.5rem; margin-bottom: 1.5rem; border-bottom: 1px solid var(--border); padding-bottom: 0.5rem; }
                .tab-btn { padding: 0.6rem 1.2rem; border-radius: 8px; font-weight: 600; font-size: 0.875rem; border: 1px solid transparent; background: transparent; cursor: pointer; color: var(--text-secondary); transition: all 0.2s ease; }
                .tab-btn:hover { background: #e2e8f0; color: var(--text-primary); }
                .tab-btn.active { background: var(--accent); color: #fff; }

                .tab-content { display: none; }
                .tab-content.active { display: block; }
                
                .section { background: var(--bg-card); border-radius: 12px; border: 1px solid var(--border); box-shadow: 0 1px 3px rgba(0,0,0,0.05); margin-bottom: 1.5rem; overflow: hidden; }
                .section-header { padding: 1rem 1.5rem; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; background: #fafafa; }
                .section-header h2 { font-size: 1.125rem; font-weight: 600; color: #1e293b; }
                
                .controls { padding: 1rem 1.5rem; border-bottom: 1px solid var(--border); display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: center; background: #fff; }
                .search-box { flex: 1; min-width: 250px; padding: 0.5rem 0.75rem; border: 1px solid var(--border); border-radius: 6px; font-size: 0.875rem; }
                .search-box:focus { outline: 2px solid var(--accent); border-color: transparent; }
                .filter-btn { padding: 0.4rem 0.8rem; border-radius: 6px; font-size: 0.8125rem; font-weight: 500; border: 1px solid var(--border); background: #f8fafc; cursor: pointer; transition: all 0.15s ease; }
                .filter-btn:hover { background: #e2e8f0; }
                .filter-btn.active { background: #0f172a; color: #fff; border-color: #0f172a; }
                
                table { width: 100%; border-collapse: collapse; text-align: left; font-size: 0.875rem; }
                th { background: #f8fafc; padding: 0.75rem 1rem; font-weight: 600; color: var(--text-secondary); border-bottom: 1px solid var(--border); text-transform: uppercase; font-size: 0.75rem; letter-spacing: 0.05em; }
                td { padding: 0.875rem 1rem; border-bottom: 1px solid var(--border); vertical-align: top; }
                tr:hover { background: #f8fafc; }
                
                .pill { display: inline-block; padding: 0.2rem 0.5rem; border-radius: 4px; font-size: 0.75rem; font-weight: 700; text-transform: uppercase; }
                .pill-error { background: var(--danger-bg); color: var(--danger); border: 1px solid var(--danger-border); }
                .pill-warning { background: var(--warning-bg); color: var(--warning); border: 1px solid var(--warning-border); }
                .pill-info { background: var(--info-bg); color: var(--info); border: 1px solid var(--info-border); }
                
                .code-loc { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 0.8125rem; color: #0284c7; }
                .snippet { background: #0f172a; color: #e2e8f0; padding: 0.75rem; border-radius: 6px; overflow-x: auto; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 0.75rem; line-height: 1.45; margin-top: 0.5rem; white-space: pre; }
                details summary { cursor: pointer; color: #0284c7; font-size: 0.8125rem; }
                .rule-code { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-weight: 600; color: #334155; }
                .remediation { margin-top: 0.35rem; font-size: 0.8125rem; color: var(--text-secondary); background: #f1f5f9; padding: 0.4rem 0.6rem; border-radius: 4px; border-left: 3px solid var(--accent); }
                
                .details-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 1rem; padding: 1.5rem; }
                .stat-box { background: #f8fafc; padding: 1rem; border-radius: 8px; border: 1px solid var(--border); }
                .stat-box h4 { font-size: 0.8125rem; font-weight: 600; color: var(--text-secondary); margin-bottom: 0.5rem; text-transform: uppercase; }
                .stat-box ul { list-style: none; }
                .stat-box li { display: flex; justify-content: space-between; padding: 0.25rem 0; font-size: 0.875rem; border-bottom: 1px dashed var(--border); }
                .stat-box li:last-child { border-bottom: none; }
                
                .chart-bar-container { margin-top: 0.5rem; background: #e2e8f0; border-radius: 9999px; height: 12px; overflow: hidden; display: flex; }
                .chart-bar-err { background: var(--danger); }
                .chart-bar-warn { background: var(--warning); }
                .chart-bar-info { background: var(--info); }
                
                .header-actions { display: flex; align-items: center; gap: 0.75rem; }
                .btn { display: inline-flex; align-items: center; gap: 0.35rem; padding: 0.45rem 0.8rem; border-radius: 6px; border: 1px solid var(--border); background: var(--bg-card); color: var(--text-primary); font-size: 0.8125rem; font-weight: 600; cursor: pointer; transition: background 0.15s ease, border-color 0.15s ease; }
                .btn:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }
                .btn:focus-visible, .filter-btn:focus-visible, .tab-btn:focus-visible, select:focus-visible, input:focus-visible, th.sortable:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
                .btn-ghost { border-color: transparent; background: transparent; font-size: 1rem; padding: 0.35rem 0.5rem; }

                .toolbar { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; padding: 1rem 1.5rem; border-bottom: 1px solid var(--border); background: #fafafa; }
                .toolbar label { font-size: 0.8125rem; color: var(--text-secondary); display: flex; align-items: center; gap: 0.35rem; }
                .toolbar select { padding: 0.4rem 0.5rem; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-card); color: var(--text-primary); font-size: 0.8125rem; }
                .result-count { margin-left: auto; font-size: 0.8125rem; color: var(--text-secondary); font-weight: 600; }
                th.sortable { cursor: pointer; user-select: none; white-space: nowrap; }
                th.sortable:hover { color: var(--accent); }
                th[aria-sort="ascending"] .sort-indicator::after { content: " ▲"; font-size: 0.65rem; }
                th[aria-sort="descending"] .sort-indicator::after { content: " ▼"; font-size: 0.65rem; }
                tr.group-row td { background: #eef2f7; font-size: 0.75rem; font-weight: 700; letter-spacing: 0.03em; color: #334155; padding: 0.5rem 1rem; text-transform: uppercase; }
                .group-count { display: inline-block; margin-left: 0.4rem; padding: 0 0.4rem; border-radius: 9999px; background: #cbd5e1; color: #0f172a; font-size: 0.6875rem; }
                tr:target { outline: 2px solid var(--accent); outline-offset: -2px; }
                .permalink { text-decoration: none; color: var(--text-secondary); margin-left: 0.35rem; opacity: 0.55; font-size: 0.75rem; }
                .permalink:hover, .permalink:focus { opacity: 1; color: var(--accent); }
                .noscript-note { background: var(--warning-bg); border: 1px solid var(--warning-border); color: var(--warning); padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1rem; font-size: 0.875rem; }
                .empty-state { padding: 1.5rem; text-align: center; color: var(--text-secondary); font-size: 0.875rem; }
                .note { padding: 1rem 1.5rem; color: var(--text-secondary); font-size: 0.8125rem; border-bottom: 1px solid var(--border); background: #fafafa; }

                #toast { position: fixed; right: 1.5rem; bottom: 1.5rem; background: #0f172a; color: #f8fafc; padding: 0.7rem 1rem; border-radius: 8px; font-size: 0.8125rem; font-weight: 600; opacity: 0; transform: translateY(0.5rem); transition: opacity 0.2s ease, transform 0.2s ease; pointer-events: none; z-index: 50; }
                #toast.show { opacity: 1; transform: translateY(0); }

                [data-theme="dark"] {
                    --bg-primary: #0b1220;
                    --bg-card: #111a2c;
                    --text-primary: #e2e8f0;
                    --text-secondary: #94a3b8;
                    --border: #273449;
                    --accent: #60a5fa;
                    --accent-light: #17233a;
                    --danger-bg: #2a1518;
                    --danger-border: #7f1d1d;
                    --warning-bg: #2a1f0d;
                    --warning-border: #78350f;
                    --info-bg: #0c2233;
                    --info-border: #0c4a6e;
                    --success-bg: #0d2417;
                    --success-border: #14532d;
                }
                [data-theme="dark"] .section-header, [data-theme="dark"] .toolbar, [data-theme="dark"] .note { background: #0f1828; }
                [data-theme="dark"] .stat-box, [data-theme="dark"] .remediation, [data-theme="dark"] .group-row td { background: #17233a; }
                [data-theme="dark"] tr:hover { background: #16213a; }
                [data-theme="dark"] .header-title h1, [data-theme="dark"] .rule-code,
                [data-theme="dark"] .section-header h2 { color: #e2e8f0; }
                [data-theme="dark"] th { background: #0f1828; color: #94a3b8; }
                [data-theme="dark"] .search-box,
                [data-theme="dark"] .filter-btn,
                [data-theme="dark"] .toolbar select { background: #0f1828; color: var(--text-primary); border-color: var(--border); }
                [data-theme="dark"] .filter-btn:hover { background: #17233a; }
                [data-theme="dark"] .filter-btn.active { background: var(--accent); color: #06121f; border-color: var(--accent); }
                [data-theme="dark"] .code-loc, [data-theme="dark"] details summary { color: #38bdf8; }
                [data-theme="dark"] .chart-bar-container { background: #273449; }
                [data-theme="dark"] .group-count { background: #334155; color: #e2e8f0; }
                [data-theme="dark"] .snippet { background: #020617; }

                @media (max-width: 720px) {
                    body { padding: 1rem; }
                    header { flex-direction: column; align-items: flex-start; gap: 1rem; }
                    .toolbar { padding: 1rem; }
                    .nav-tabs { flex-wrap: wrap; }
                    .result-count { margin-left: 0; width: 100%; }
                }
                @media print {
                    body { background: #fff; color: #000; padding: 0; }
                    .container { max-width: none; }
                    .nav-tabs, .toolbar, .controls, .header-actions, .permalink, #toast, .noscript-note { display: none !important; }
                    .tab-content { display: block !important; }
                    .section { box-shadow: none; border-color: #ccc; page-break-inside: avoid; }
                    .kpi-grid { grid-template-columns: repeat(3, 1fr); }
                    a { color: #000; text-decoration: none; }
                    tr { page-break-inside: avoid; }
                }

                footer { text-align: center; color: var(--text-secondary); font-size: 0.8125rem; margin-top: 2rem; }
                """);
        sb.append("</style>\n</head>\n<body>\n<div class=\"container\">\n");
        sb.append("<noscript><style>.tab-content{display:block !important;} .nav-tabs{display:none !important;}</style>")
                .append("<p class=\"noscript-note\">JavaScript is disabled: every section is shown expanded, but filtering, sorting and exports are unavailable.</p></noscript>\n");

        // Header
        sb.append("<header>\n");
        sb.append("  <div class=\"header-title\">\n");
        sb.append("    <h1>⚡ AEM Code Analyser</h1>\n");
        sb.append("    <p>Project Root: <code>").append(escape(report.projectRoot().toString())).append("</code> &bull; Generated: ")
                .append(ISO_FORMATTER.format(Instant.now(clock))).append(" &bull; Duration: ")
                .append(String.format("%.2fs", report.durationMillis() / 1000.0))
                .append(" &bull; Tool: v").append(escape(VersionInfo.current())).append("</p>\n");
        sb.append("  </div>\n");
        sb.append("  <div class=\"header-actions\">\n");
        sb.append("    <button id=\"themeToggle\" class=\"btn btn-ghost\" type=\"button\" aria-label=\"Toggle dark mode\" title=\"Toggle dark mode\">🌙</button>\n");
        sb.append("    <span class=\"status-badge ").append(statusBadgeClass).append("\">").append(statusText).append("</span>\n");
        sb.append("  </div>\n");
        sb.append("</header>\n");

        // KPI Grid
        sb.append("<div class=\"kpi-grid\">\n");
        sb.append("  <div class=\"kpi-card\">\n    <div class=\"label\">Total Violations</div>\n    <div class=\"value\">")
                .append(totalViolations).append("</div>\n  </div>\n");
        sb.append("  <div class=\"kpi-card err\">\n    <div class=\"label\">Errors</div>\n    <div class=\"value\">")
                .append(errors).append("</div>\n  </div>\n");
        sb.append("  <div class=\"kpi-card warn\">\n    <div class=\"label\">Warnings</div>\n    <div class=\"value\">")
                .append(warnings).append("</div>\n  </div>\n");
        sb.append("  <div class=\"kpi-card info\">\n    <div class=\"label\">Info</div>\n    <div class=\"value\">")
                .append(infos).append("</div>\n  </div>\n");
        if (!acceptedViolations.isEmpty()) {
            sb.append("  <div class=\"kpi-card accepted\">\n    <div class=\"label\">Accepted by Baseline</div>\n    <div class=\"value\">")
                    .append(acceptedViolations.size())
                    .append("</div>\n    <div style=\"font-size:0.75rem;color:var(--text-secondary);margin-top:2px;\">not counted in totals</div>\n  </div>\n");
        }

        var dupMetrics = report.codeMetrics();
        sb.append("  <div class=\"kpi-card dup\">\n    <div class=\"label\">Code Duplication</div>\n    <div class=\"value\">")
                .append(String.format("%.1f%%", dupMetrics.duplicationPercent()))
                .append("</div>\n    <div style=\"font-size:0.75rem;color:var(--text-secondary);margin-top:2px;\">")
                .append(String.format("%,d lines / %,d classes", dupMetrics.duplicatedLines(), dupMetrics.duplicateClasses().size()))
                .append("</div>\n  </div>\n");

        var cov = report.coverageReport();
        if (cov != null && cov.present()) {
            sb.append("  <div class=\"kpi-card cov\">\n    <div class=\"label\">Test Coverage</div>\n    <div class=\"value\">")
                    .append(String.format("%.1f%%", cov.lineCoveragePercent()))
                    .append("</div>\n    <div style=\"font-size:0.75rem;color:var(--text-secondary);margin-top:2px;\">")
                    .append(String.format("%,d / %,d lines", cov.coveredLines(), cov.totalLines()))
                    .append("</div>\n  </div>\n");
        } else {
            sb.append("  <div class=\"kpi-card\">\n    <div class=\"label\">Test Coverage</div>\n    <div class=\"value\" style=\"font-size:1.1rem;color:var(--text-secondary);margin-top:0.5rem;\">N/A</div>\n    <div style=\"font-size:0.75rem;color:var(--text-secondary);margin-top:2px;\">Run with --collect-coverage</div>\n  </div>\n");
        }
        sb.append("</div>\n");

        // Navigation Tabs
        sb.append("<div class=\"nav-tabs\" role=\"tablist\" aria-label=\"Report sections\">\n");
        sb.append("  <button class=\"tab-btn active\" role=\"tab\" aria-selected=\"true\" aria-controls=\"tab-violations\" id=\"tabbtn-violations\" data-tab=\"tab-violations\">Violations Explorer (").append(totalViolations).append(")</button>\n");
        sb.append("  <button class=\"tab-btn\" role=\"tab\" aria-selected=\"false\" aria-controls=\"tab-overview\" id=\"tabbtn-overview\" data-tab=\"tab-overview\">Overview & Topology</button>\n");
        sb.append("  <button class=\"tab-btn\" role=\"tab\" aria-selected=\"false\" aria-controls=\"tab-health\" id=\"tabbtn-health\" data-tab=\"tab-health\">Run Health (").append(diagnosticsCount + acceptedViolations.size()).append(")</button>\n");
        sb.append("  <button class=\"tab-btn\" role=\"tab\" aria-selected=\"false\" aria-controls=\"tab-duplication\" id=\"tabbtn-duplication\" data-tab=\"tab-duplication\">Duplication Breakdown</button>\n");
        sb.append("  <button class=\"tab-btn\" role=\"tab\" aria-selected=\"false\" aria-controls=\"tab-coverage\" id=\"tabbtn-coverage\" data-tab=\"tab-coverage\">Test Coverage</button>\n");
        sb.append("  <button class=\"tab-btn\" role=\"tab\" aria-selected=\"false\" aria-controls=\"tab-autofix\" id=\"tabbtn-autofix\" data-tab=\"tab-autofix\">Auto-Fix (--fix)</button>\n");
        sb.append("</div>\n");

        // TAB 1: VIOLATIONS
        sb.append("<div id=\"tab-violations\" class=\"tab-content active\" role=\"tabpanel\" aria-labelledby=\"tabbtn-violations\">\n");
        sb.append("<div class=\"section\">\n");
        sb.append("  <div class=\"section-header\">\n");
        sb.append("    <h2>Active Violations (").append(totalViolations).append(")</h2>\n");
        sb.append("  </div>\n");

        // Controls
        sb.append("  <div class=\"toolbar\" role=\"toolbar\" aria-label=\"Finding filters\">\n");
        sb.append("    <input type=\"text\" id=\"searchInput\" class=\"search-box\" placeholder=\"Search by file, rule, message, or keyword...\" aria-label=\"Search findings\">\n");
        sb.append("    <button class=\"filter-btn active\" data-filter=\"all\">All</button>\n");
        sb.append("    <button class=\"filter-btn\" data-filter=\"ERROR\">Errors Only</button>\n");
        sb.append("    <button class=\"filter-btn\" data-filter=\"WARNING\">Warnings Only</button>\n");
        sb.append("    <button class=\"filter-btn\" data-filter=\"INFO\">Info Only</button>\n");
        sb.append("    <button class=\"filter-btn\" data-category=\"PERF\">Session Leaks (").append(categoryCounts.getOrDefault("PERF", 0L)).append(")</button>\n");
        sb.append("    <button class=\"filter-btn\" data-category=\"CLOUD\">Cloud Service (").append(categoryCounts.getOrDefault("CLOUD", 0L)).append(")</button>\n");
        sb.append("    <button class=\"filter-btn\" data-category=\"SEC\">Security (").append(categoryCounts.getOrDefault("SEC", 0L)).append(")</button>\n");
        sb.append("    <button class=\"filter-btn\" data-category=\"DEAD\">Dead Code (").append(categoryCounts.getOrDefault("DEAD", 0L)).append(")</button>\n");
        sb.append("    <button class=\"filter-btn\" data-category=\"DUP\">Duplicates (").append(categoryCounts.getOrDefault("DUP", 0L)).append(")</button>\n");
        sb.append("    <button class=\"filter-btn\" data-category=\"SONAR\">Sonar Java (").append(categoryCounts.getOrDefault("SONAR", 0L)).append(")</button>\n");
        sb.append("    <label for=\"groupBy\">Group by\n");
        sb.append("      <select id=\"groupBy\">\n        <option value=\"none\">None</option>\n        <option value=\"rule\">Rule</option>\n        <option value=\"file\">File</option>\n      </select>\n    </label>\n");
        sb.append("    <button id=\"resetFilters\" class=\"btn\" type=\"button\">Reset</button>\n");
        sb.append("    <button id=\"exportCsv\" class=\"btn\" type=\"button\">CSV</button>\n");
        sb.append("    <button id=\"exportJson\" class=\"btn\" type=\"button\">JSON</button>\n");
        sb.append("    <button id=\"copyMarkdown\" class=\"btn\" type=\"button\">Copy summary</button>\n");
        sb.append("    <button id=\"copyLink\" class=\"btn\" type=\"button\">Copy link</button>\n");
        sb.append("    <span id=\"resultCount\" class=\"result-count\" aria-live=\"polite\"></span>\n");
        sb.append("  </div>\n");

        // Table
        sb.append("  <div style=\"overflow-x:auto;\">\n");
        sb.append("    <table id=\"violationsTable\">\n");
        sb.append("      <thead>\n        <tr>\n");
        sb.append("          <th scope=\"col\" class=\"sortable\" data-sort=\"severity\" tabindex=\"0\" style=\"width:110px;\">Severity <span class=\"sort-indicator\"></span></th>\n");
        sb.append("          <th scope=\"col\" class=\"sortable\" data-sort=\"rule\" tabindex=\"0\" style=\"width:160px;\">Rule <span class=\"sort-indicator\"></span></th>\n");
        sb.append("          <th scope=\"col\" class=\"sortable\" data-sort=\"path\" tabindex=\"0\">File Location <span class=\"sort-indicator\"></span></th>\n");
        sb.append("          <th scope=\"col\" data-sort=\"line\">Issue & Remediation</th>\n");
        sb.append("        </tr>\n      </thead>\n      <tbody>\n");

        if (sortedViolations.isEmpty()) {
            sb.append("        <tr><td colspan=\"4\" style=\"text-align:center;padding:2rem;color:var(--success);font-weight:600;\">🎉 Clean build! No architectural or quality violations detected.</td></tr>\n");
        } else {
            int index = 0;
            for (Violation v : sortedViolations) {
                if (index >= maxViolations) {
                    break;
                }
                index++;
                String pillClass = switch (v.severity()) {
                    case ERROR -> "pill-error";
                    case WARNING -> "pill-warning";
                    case INFO -> "pill-info";
                };
                String cat = getRuleCategory(v.ruleId());

                sb.append("        <tr class=\"violation-row\" id=\"f-").append(index).append("\"")
                        .append(" data-severity=\"").append(v.severity().name()).append("\"")
                        .append(" data-category=\"").append(cat).append("\"")
                        .append(" data-rule=\"").append(escape(v.ruleId())).append("\"")
                        .append(" data-path=\"").append(escape(v.filePath())).append("\"")
                        .append(" data-line=\"").append(v.region().startLine()).append("\"")
                        .append(" data-message=\"").append(escape(v.message())).append("\"")
                        .append(" data-remediation=\"").append(escape(v.remediationOpt().orElse(""))).append("\">\n");
                sb.append("          <td><span class=\"pill ").append(pillClass).append("\">").append(v.severity()).append("</span></td>\n");
                sb.append("          <td><span class=\"rule-code\">").append(escape(v.ruleId())).append("</span>")
                        .append("<a class=\"permalink\" href=\"#f-").append(index)
                        .append("\" title=\"Link to this finding\" aria-label=\"Link to this finding\">#</a></td>\n");
                sb.append("          <td><span class=\"code-loc\">").append(escape(v.filePath())).append(":").append(v.region().startLine()).append("</span></td>\n");
                sb.append("          <td>\n");
                sb.append("            <div><strong>").append(escape(v.message())).append("</strong></div>\n");
                v.remediationOpt().ifPresent(rem ->
                        sb.append("            <div class=\"remediation\">💡 <strong>Remediation:</strong> ").append(escape(rem)).append("</div>\n"));
                if (!v.relatedLocations().isEmpty()) {
                    sb.append("            <div style=\"font-size:0.75rem;color:var(--text-secondary);margin-top:0.25rem;\">Related: ");
                    v.relatedLocations().forEach(rel ->
                            sb.append("<span class=\"code-loc\">").append(escape(rel.filePath())).append(":").append(rel.region().startLine()).append("</span> "));
                    sb.append("</div>\n");
                }
                sb.append("          </td>\n");
                sb.append("        </tr>\n");
            }
            if (sortedViolations.size() > maxViolations) {
                sb.append("        <tr class=\"omitted-row\"><td colspan=\"4\" style=\"text-align:center;padding:1rem;color:var(--text-secondary);\">")
                        .append(sortedViolations.size() - maxViolations)
                        .append(" more violation(s) omitted from this dashboard; use --format SARIF or SONAR for the full set.")
                        .append("</td></tr>\n");
            }
        }
        sb.append("      </tbody>\n    </table>\n  </div>\n</div>\n</div>\n");

        // TAB 2: OVERVIEW & TOPOLOGY
        sb.append("<div id=\"tab-overview\" class=\"tab-content\" role=\"tabpanel\" aria-labelledby=\"tabbtn-overview\">\n");
        sb.append("<div class=\"section\">\n");
        sb.append("  <div class=\"section-header\">\n    <h2>Architecture & Quality Overview</h2>\n  </div>\n");
        sb.append("  <div class=\"details-grid\">\n");
        sb.append("    <div class=\"stat-box\">\n      <h4>Severity Distribution</h4>\n");
        if (totalViolations > 0) {
            double errPct = (errors * 100.0) / totalViolations;
            double warnPct = (warnings * 100.0) / totalViolations;
            double infoPct = (infos * 100.0) / totalViolations;
            sb.append("      <div class=\"chart-bar-container\">\n");
            if (errors > 0) sb.append("        <div class=\"chart-bar-err\" style=\"width:").append(errPct).append("%;\"></div>\n");
            if (warnings > 0) sb.append("        <div class=\"chart-bar-warn\" style=\"width:").append(warnPct).append("%;\"></div>\n");
            if (infos > 0) sb.append("        <div class=\"chart-bar-info\" style=\"width:").append(infoPct).append("%;\"></div>\n");
            sb.append("      </div>\n");
        }
        sb.append("      <ul style=\"margin-top:1rem;\">\n");
        sb.append("        <li><span>Errors</span><strong style=\"color:var(--danger);\">").append(errors).append("</strong></li>\n");
        sb.append("        <li><span>Warnings</span><strong style=\"color:var(--warning);\">").append(warnings).append("</strong></li>\n");
        sb.append("        <li><span>Info</span><strong style=\"color:var(--info);\">").append(infos).append("</strong></li>\n");
        sb.append("      </ul>\n    </div>\n");

        sb.append("    <div class=\"stat-box\">\n      <h4>Repository Topology</h4>\n      <ul>\n");
        if (!report.scanStatistics().isEmpty()) {
            report.scanStatistics().forEach((k, val) ->
                    sb.append("        <li><span>").append(escape(k)).append("</span><strong>").append(escape(val.toString())).append("</strong></li>\n"));
        }
        sb.append("      </ul>\n    </div>\n");

        sb.append("    <div class=\"stat-box\">\n      <h4>Codebase Metrics</h4>\n      <ul>\n");
        sb.append("        <li><span>Total Java Classes</span><strong>").append(dupMetrics.totalJavaFiles()).append("</strong></li>\n");
        sb.append("        <li><span>Total Java Lines</span><strong>").append(dupMetrics.totalJavaLines()).append("</strong></li>\n");
        sb.append("        <li><span>Duplicate Method Blocks</span><strong>").append(dupMetrics.totalMethodBlocks()).append("</strong></li>\n");
        sb.append("        <li><span>Total Duplicated Lines</span><strong>").append(dupMetrics.duplicatedLines()).append("</strong></li>\n");
        sb.append("      </ul>\n    </div>\n");
        sb.append("  </div>\n</div>\n");

        // Run history / trend
        if (!history.isEmpty()) {
            sb.append("<div class=\"section\">\n  <div class=\"section-header\">\n");
            sb.append("    <h2>Run History (last ").append(history.size()).append(")</h2>\n  </div>\n");
            sb.append("  <div style=\"overflow-x:auto;\">\n    <table>\n      <thead><tr>\n");
            sb.append("        <th scope=\"col\">Run</th><th scope=\"col\">Total</th><th scope=\"col\">Errors</th><th scope=\"col\">Warnings</th>")
                    .append("<th scope=\"col\">Info</th><th scope=\"col\">Duplication</th><th scope=\"col\">Duration</th>\n");
            sb.append("      </tr></thead>\n      <tbody>\n");
            int run = 1;
            for (RunSummary summary : history) {
                sb.append("        <tr>\n");
                sb.append("          <td>#").append(run++).append(" <span style=\"font-size:0.75rem;color:var(--text-secondary);\">")
                        .append(escape(summary.timestamp())).append("</span></td>\n");
                sb.append("          <td><strong>").append(summary.total()).append("</strong></td>\n");
                sb.append("          <td>").append(summary.errors()).append("</td>\n");
                sb.append("          <td>").append(summary.warnings()).append("</td>\n");
                sb.append("          <td>").append(summary.infos()).append("</td>\n");
                sb.append("          <td>").append(String.format("%.1f%%", summary.duplicationPercent())).append("</td>\n");
                sb.append("          <td>").append(String.format("%.1f s", summary.durationMillis() / 1000.0)).append("</td>\n");
                sb.append("        </tr>\n");
            }
            sb.append("      </tbody>\n    </table>\n  </div>\n</div>\n");
        }
        sb.append("</div>\n");

        // TAB: RUN HEALTH (diagnostics + baseline-accepted findings)
        sb.append("<div id=\"tab-health\" class=\"tab-content\" role=\"tabpanel\" aria-labelledby=\"tabbtn-health\">\n");
        sb.append("<div class=\"section\">\n");
        sb.append("  <div class=\"section-header\">\n    <h2>Run Diagnostics (").append(diagnosticsCount).append(")</h2>\n  </div>\n");
        if (report.diagnostics().isEmpty()) {
            sb.append("  <div class=\"empty-state\">No parser or scan diagnostics: every discovered file was analysed.</div>\n");
        } else {
            sb.append("  <div class=\"note\">Diagnostics are non-fatal problems. A reported file was skipped or only partially analysed, so a clean result may still hide issues in it.</div>\n");
            sb.append("  <div style=\"overflow-x:auto;\">\n    <table>\n      <thead><tr>\n");
            sb.append("        <th scope=\"col\" style=\"width:190px;\">Kind</th><th scope=\"col\">Location</th><th scope=\"col\">Detail</th>\n");
            sb.append("      </tr></thead>\n      <tbody>\n");
            for (var diagnostic : report.diagnostics()) {
                String location = diagnostic.line() > 0
                        ? diagnostic.path() + ":" + diagnostic.line()
                        : diagnostic.path();
                sb.append("        <tr>\n");
                sb.append("          <td><span class=\"pill pill-warning\">").append(escape(diagnostic.kind().name())).append("</span></td>\n");
                sb.append("          <td><span class=\"code-loc\">").append(escape(location)).append("</span></td>\n");
                sb.append("          <td>").append(escape(diagnostic.reason())).append("</td>\n");
                sb.append("        </tr>\n");
            }
            sb.append("      </tbody>\n    </table>\n  </div>\n");
        }
        sb.append("</div>\n");

        sb.append("<div class=\"section\">\n");
        sb.append("  <div class=\"section-header\">\n    <h2>Accepted by Baseline (").append(acceptedViolations.size()).append(")</h2>\n  </div>\n");
        if (acceptedViolations.isEmpty()) {
            sb.append("  <div class=\"empty-state\">No findings are suppressed by a baseline.</div>\n");
        } else {
            sb.append("  <div class=\"note\">These findings are frozen in <code>.aem-analyser-baseline.json</code> and excluded from totals and exit codes. Run without <code>--baseline-report</code> to hide them entirely.</div>\n");
            sb.append("  <div style=\"overflow-x:auto;\">\n    <table>\n      <thead><tr>\n");
            sb.append("        <th scope=\"col\" style=\"width:160px;\">Rule</th><th scope=\"col\">Location</th><th scope=\"col\">Message</th>\n");
            sb.append("      </tr></thead>\n      <tbody>\n");
            for (Violation violation : acceptedViolations) {
                sb.append("        <tr>\n");
                sb.append("          <td><span class=\"rule-code\">").append(escape(violation.ruleId())).append("</span></td>\n");
                sb.append("          <td><span class=\"code-loc\">").append(escape(violation.filePath())).append(":").append(violation.region().startLine()).append("</span></td>\n");
                sb.append("          <td>").append(escape(violation.message())).append("</td>\n");
                sb.append("        </tr>\n");
            }
            sb.append("      </tbody>\n    </table>\n  </div>\n");
        }
        sb.append("</div>\n</div>\n");

        // TAB 3: DUPLICATION
        sb.append("<div id=\"tab-duplication\" class=\"tab-content\" role=\"tabpanel\" aria-labelledby=\"tabbtn-duplication\">\n");
        sb.append("<div class=\"section\">\n");
        sb.append("  <div class=\"section-header\">\n");
        sb.append("    <h2>PMD CPD Duplication Breakdown (").append(dupMetrics.duplicateClasses().size()).append(" Classes)</h2>\n");
        sb.append("  </div>\n");
        sb.append("  <div style=\"overflow-x:auto;\">\n");
        sb.append("    <table>\n      <thead>\n        <tr>\n");
        sb.append("          <th>Class Name</th>\n");
        sb.append("          <th style=\"width:180px;\">Duplicate Lines</th>\n");
        sb.append("          <th style=\"width:180px;\">Class Duplication %</th>\n");
        sb.append("        </tr>\n      </thead>\n      <tbody>\n");
        if (dupMetrics.duplicateClasses().isEmpty()) {
            sb.append("        <tr><td colspan=\"3\" style=\"text-align:center;padding:2rem;color:var(--success);\">Zero duplicated code clones detected!</td></tr>\n");
        } else {
            for (var dupClass : dupMetrics.duplicateClasses()) {
                sb.append("        <tr>\n");
                sb.append("          <td><code>").append(escape(dupClass.className())).append("</code></td>\n");
                sb.append("          <td><strong>").append(dupClass.duplicatedLines()).append("</strong> lines</td>\n");
                sb.append("          <td>").append(String.format("%.1f%%", dupClass.duplicationPercent())).append("</td>\n");
                sb.append("        </tr>\n");
            }
        }
        sb.append("      </tbody>\n    </table>\n  </div>\n</div>\n");

        // Duplicate block details: what the duplicated lines are and where they came from.
        if (!dupMetrics.duplicateBlockDetails().isEmpty()) {
            sb.append("<div class=\"section\">\n");
            sb.append("  <div class=\"section-header\">\n");
            sb.append("    <h2>Duplicate Blocks (").append(dupMetrics.duplicateBlockDetails().size()).append(")</h2>\n");
            sb.append("  </div>\n  <div style=\"overflow-x:auto;\">\n");
            sb.append("    <table>\n      <thead>\n        <tr>\n");
            sb.append("          <th>Kind</th>\n");
            sb.append("          <th>Duplicate</th>\n");
            sb.append("          <th>Original</th>\n");
            sb.append("          <th>Lines</th>\n");
            sb.append("          <th>Code lines</th>\n");
            sb.append("          <th>Tokens</th>\n");
            sb.append("          <th>Fingerprint</th>\n");
            sb.append("          <th>Preview</th>\n");
            sb.append("        </tr>\n      </thead>\n      <tbody>\n");
            for (CodeMetrics.DuplicateBlock block : dupMetrics.duplicateBlockDetails()) {
                String kind = classifyDuplicate(report.projectRoot(), block);
                sb.append("        <tr>\n");
                sb.append("          <td><span class=\"pill pill-info\">").append(escape(kind)).append("</span></td>\n");
                sb.append("          <td><span class=\"code-loc\">").append(escape(block.duplicateFile()))
                        .append(':').append(block.duplicateStartLine()).append('-').append(block.duplicateEndLine())
                        .append("</span></td>\n");
                sb.append("          <td><span class=\"code-loc\">").append(escape(block.originalFile()))
                        .append(':').append(block.originalStartLine()).append('-').append(block.originalEndLine())
                        .append("</span></td>\n");
                sb.append("          <td><strong>").append(block.duplicatedLines()).append("</strong></td>\n");
                sb.append("          <td>").append(block.codeLines()).append("</td>\n");
                sb.append("          <td>").append(block.tokens()).append("</td>\n");
                sb.append("          <td><code>").append(escape(block.fingerprint())).append("</code></td>\n");
                sb.append("          <td><details><summary>Show duplicated lines</summary><pre class=\"snippet\">")
                        .append(escape(previewLines(report.projectRoot(), block)))
                        .append("</pre></details></td>\n");
                sb.append("        </tr>\n");
            }
            sb.append("      </tbody>\n    </table>\n  </div>\n</div>\n");
        }
        sb.append("</div>\n");

        // TAB 4: COVERAGE
        sb.append("<div id=\"tab-coverage\" class=\"tab-content\" role=\"tabpanel\" aria-labelledby=\"tabbtn-coverage\">\n");
        sb.append("<div class=\"section\">\n");
        sb.append("  <div class=\"section-header\">\n    <h2>JaCoCo Test Coverage Status</h2>\n  </div>\n");
        sb.append("  <div class=\"details-grid\">\n");
        if (cov != null && cov.present()) {
            sb.append("    <div class=\"stat-box\">\n      <h4>Coverage Summary</h4>\n      <ul>\n");
            sb.append("        <li><span>Line Coverage</span><strong>").append(String.format("%.1f%%", cov.lineCoveragePercent())).append("</strong></li>\n");
            sb.append("        <li><span>Covered / Total Lines</span><strong>").append(cov.coveredLines()).append(" / ").append(cov.totalLines()).append("</strong></li>\n");
            sb.append("        <li><span>Branch Coverage</span><strong>").append(String.format("%.1f%%", cov.branchCoveragePercent())).append("</strong></li>\n");
            sb.append("        <li><span>Covered / Total Branches</span><strong>").append(cov.coveredBranches()).append(" / ").append(cov.totalBranches()).append("</strong></li>\n");
            if (cov.sourcePath() != null) {
                sb.append("        <li><span>Report File</span><strong style=\"font-size:0.75rem;\">").append(escape(cov.sourcePath())).append("</strong></li>\n");
            }
            sb.append("      </ul>\n    </div>\n");
        } else {
            sb.append("    <div class=\"stat-box\" style=\"grid-column: 1 / -1; padding: 2rem; text-align: center;\">\n");
            sb.append("      <p style=\"color:var(--text-secondary);font-size:1rem;\">No JaCoCo coverage file was detected in standard target directories.<br>Run with <code>--collect-coverage</code> to execute unit tests and generate coverage artifacts automatically.</p>\n");
            sb.append("    </div>\n");
        }
        sb.append("  </div>\n</div>\n</div>\n");

        // TAB 5: AUTO-FIX
        sb.append("<div id=\"tab-autofix\" class=\"tab-content\" role=\"tabpanel\" aria-labelledby=\"tabbtn-autofix\">\n");
        sb.append("<div class=\"section\">\n");
        sb.append("  <div class=\"section-header\">\n    <h2>Safe Automated Remediation Engine (--fix)</h2>\n  </div>\n");
        sb.append("  <div style=\"padding:1.5rem;\">\n");
        sb.append("    <p style=\"margin-bottom:1rem;color:var(--text-secondary);\">The analyzer includes an integrated auto-fix engine capable of safely resolving deterministic code smells in place:</p>\n");
        sb.append("    <ul style=\"margin-left:1.5rem;color:var(--text-primary);line-height:1.8;\">\n");
        sb.append("      <li><strong>java:S1128 (Duplicate Imports):</strong> Automatically detects and removes redundant duplicate import statements while preserving package and class layout.</li>\n");
        sb.append("      <li><strong>java:S1125 (Boolean Literals):</strong> Cleans up redundant <code>== true</code> and <code>== false</code> comparisons.</li>\n");
        sb.append("      <li><strong>java:S4719 (StandardCharsets):</strong> Replaces literal <code>\"UTF-8\"</code> in <code>.getBytes()</code> with <code>StandardCharsets.UTF_8</code> and inserts necessary imports.</li>\n");
        sb.append("    </ul>\n");
        sb.append("    <div style=\"margin-top:1.5rem;background:#f1f5f9;padding:1rem;border-radius:8px;border-left:4px solid var(--accent);\">\n");
        sb.append("      <p>💡 <strong>Usage:</strong> Run <code>aem-analyser --fix</code> to apply safe repairs, or <code>aem-analyser --fix-dry-run</code> to preview changes without modifying files.</p>\n");
        sb.append("    </div>\n");
        sb.append("  </div>\n</div>\n</div>\n");

        // Footer
        sb.append("<footer>\n");
        sb.append("  <p>Generated by AEM Code Analyser v").append(escape(VersionInfo.current()))
                .append(" &bull; Zero-config Inner-loop Engine &bull; Strictly <code>io.github.rohithv16</code></p>\n");
        sb.append("</footer>\n");
        sb.append("<div id=\"toast\" role=\"status\" aria-live=\"polite\"></div>\n");

        // Interactive JavaScript
        sb.append("""
                <script>
                (function () {
                    'use strict';

                    var root = document.documentElement;
                    var themeToggle = document.getElementById('themeToggle');
                    var toast = document.getElementById('toast');
                    var searchInput = document.getElementById('searchInput');
                    var filterButtons = Array.prototype.slice.call(document.querySelectorAll('.filter-btn'));
                    var tabButtons = Array.prototype.slice.call(document.querySelectorAll('.tab-btn'));
                    var tabContents = Array.prototype.slice.call(document.querySelectorAll('.tab-content'));
                    var groupBy = document.getElementById('groupBy');
                    var resultCount = document.getElementById('resultCount');
                    var table = document.getElementById('violationsTable');
                    var tbody = table ? table.querySelector('tbody') : null;

                    var activeFilter = 'all';
                    var activeCategory = null;
                    var groupMode = 'none';
                    var sortKey = 'severity';
                    var sortDir = 'asc';
                    var activeTab = 'tab-violations';

                    function showToast(message) {
                        if (!toast) return;
                        toast.textContent = message;
                        toast.classList.add('show');
                        window.setTimeout(function () { toast.classList.remove('show'); }, 1800);
                    }

                    // --- Theme -------------------------------------------------
                    function readTheme() {
                        try { return localStorage.getItem('aem-analyser-theme'); } catch (e) { return null; }
                    }
                    function storeTheme(value) {
                        try { localStorage.setItem('aem-analyser-theme', value); } catch (e) { /* ignore */ }
                    }
                    function applyTheme(value) {
                        root.setAttribute('data-theme', value);
                        if (themeToggle) {
                            themeToggle.textContent = value === 'dark' ? '\\u2600\\uFE0F' : '\\uD83C\\uDF19';
                            themeToggle.setAttribute('aria-pressed', value === 'dark' ? 'true' : 'false');
                        }
                    }
                    var initialTheme = readTheme();
                    if (!initialTheme) {
                        initialTheme = window.matchMedia
                            && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
                    }
                    applyTheme(initialTheme);
                    if (themeToggle) {
                        themeToggle.addEventListener('click', function () {
                            var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
                            applyTheme(next);
                            storeTheme(next);
                        });
                    }

                    // --- Tabs --------------------------------------------------
                    function activateTab(id, focus) {
                        var found = false;
                        tabButtons.forEach(function (btn) {
                            var selected = btn.getAttribute('data-tab') === id;
                            btn.classList.toggle('active', selected);
                            btn.setAttribute('aria-selected', selected ? 'true' : 'false');
                            btn.setAttribute('tabindex', selected ? '0' : '-1');
                            if (selected && focus) btn.focus();
                            found = found || selected;
                        });
                        if (!found) id = 'tab-violations';
                        tabContents.forEach(function (content) {
                            content.classList.toggle('active', content.id === id);
                        });
                        activeTab = id;
                    }

                    tabButtons.forEach(function (btn, index) {
                        btn.addEventListener('click', function () {
                            activateTab(btn.getAttribute('data-tab'), false);
                            syncHash();
                        });
                        btn.addEventListener('keydown', function (event) {
                            var next = null;
                            if (event.key === 'ArrowRight') next = (index + 1) % tabButtons.length;
                            if (event.key === 'ArrowLeft') next = (index - 1 + tabButtons.length) % tabButtons.length;
                            if (event.key === 'Home') next = 0;
                            if (event.key === 'End') next = tabButtons.length - 1;
                            if (next !== null) {
                                event.preventDefault();
                                activateTab(tabButtons[next].getAttribute('data-tab'), true);
                                syncHash();
                            }
                        });
                    });

                    // --- Rows, filtering and grouping ---------------------------
                    function rows() {
                        return Array.prototype.slice.call(tbody.querySelectorAll('tr.violation-row'));
                    }

                    function matches(row) {
                        var severity = row.getAttribute('data-severity');
                        var category = row.getAttribute('data-category');
                        var query = searchInput ? searchInput.value.toLowerCase().trim() : '';
                        if (activeFilter !== 'all' && severity !== activeFilter) return false;
                        if (activeCategory && category !== activeCategory) return false;
                        if (query && row.textContent.toLowerCase().indexOf(query) === -1) return false;
                        return true;
                    }

                    function updateCount() {
                        if (!resultCount) return;
                        var list = rows();
                        var visible = list.filter(function (row) { return !row.hidden; }).length;
                        resultCount.textContent = visible + ' of ' + list.length + ' finding(s) shown';
                    }

                    function rebuildGroups() {
                        if (!tbody) return;
                        Array.prototype.slice.call(tbody.querySelectorAll('tr.group-row'))
                            .forEach(function (row) { row.remove(); });
                        if (groupMode === 'none') return;

                        var currentKey = null;
                        var header = null;
                        var count = 0;

                        rows().forEach(function (row) {
                            var key = groupMode === 'rule'
                                ? row.getAttribute('data-rule')
                                : row.getAttribute('data-path');
                            if (key !== currentKey) {
                                if (header) header.querySelector('.group-count').textContent = count;
                                header = document.createElement('tr');
                                header.className = 'group-row';
                                var cell = document.createElement('td');
                                cell.colSpan = 4;
                                var label = document.createElement('span');
                                label.className = 'group-label';
                                label.textContent = key;
                                var badge = document.createElement('span');
                                badge.className = 'group-count';
                                badge.textContent = '0';
                                cell.appendChild(label);
                                cell.appendChild(badge);
                                header.appendChild(cell);
                                count = 0;
                                currentKey = key;
                                row.parentNode.insertBefore(header, row);
                            }
                            if (!row.hidden) count++;
                        });
                        if (header) header.querySelector('.group-count').textContent = count;
                        Array.prototype.slice.call(tbody.querySelectorAll('tr.group-row'))
                            .forEach(function (row) {
                                if (row.querySelector('.group-count').textContent === '0') row.hidden = true;
                            });
                    }

                    function applyFilters() {
                        if (!tbody) return;
                        rows().forEach(function (row) { row.hidden = !matches(row); });
                        rebuildGroups();
                        updateCount();
                        syncHash();
                    }

                    // --- Sorting ------------------------------------------------
                    function severityRank(value) {
                        return value === 'ERROR' ? 0 : value === 'WARNING' ? 1 : 2;
                    }

                    function updateSortIndicators() {
                        if (!table) return;
                        Array.prototype.slice.call(table.querySelectorAll('th.sortable')).forEach(function (th) {
                            var active = th.getAttribute('data-sort') === sortKey;
                            th.setAttribute('aria-sort', active
                                ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none');
                        });
                    }

                    function sortRows(key, direction) {
                        if (!tbody) return;
                        sortKey = key;
                        sortDir = direction;
                        var list = rows();
                        list.sort(function (a, b) {
                            var left;
                            var right;
                            if (key === 'severity') {
                                left = severityRank(a.getAttribute('data-severity'));
                                right = severityRank(b.getAttribute('data-severity'));
                            } else if (key === 'rule') {
                                left = a.getAttribute('data-rule');
                                right = b.getAttribute('data-rule');
                            } else if (key === 'line') {
                                left = Number(a.getAttribute('data-line'));
                                right = Number(b.getAttribute('data-line'));
                            } else {
                                left = a.getAttribute('data-path').toLowerCase();
                                right = b.getAttribute('data-path').toLowerCase();
                            }
                            if (left < right) return direction === 'asc' ? -1 : 1;
                            if (left > right) return direction === 'asc' ? 1 : -1;
                            var aTie = a.getAttribute('data-rule') + a.getAttribute('data-path')
                                + a.getAttribute('data-line');
                            var bTie = b.getAttribute('data-rule') + b.getAttribute('data-path')
                                + b.getAttribute('data-line');
                            return aTie < bTie ? -1 : (aTie > bTie ? 1 : 0);
                        });
                        var omitted = tbody.querySelector('tr.omitted-row');
                        list.forEach(function (row) { tbody.insertBefore(row, omitted || null); });
                        updateSortIndicators();
                        rebuildGroups();
                        syncHash();
                    }

                    if (table) {
                        Array.prototype.slice.call(table.querySelectorAll('th.sortable')).forEach(function (th) {
                            function trigger() {
                                var key = th.getAttribute('data-sort');
                                sortRows(key, key === sortKey && sortDir === 'asc' ? 'desc' : 'asc');
                            }
                            th.addEventListener('click', trigger);
                            th.addEventListener('keydown', function (event) {
                                if (event.key === 'Enter' || event.key === ' ') {
                                    event.preventDefault();
                                    trigger();
                                }
                            });
                        });
                    }

                    filterButtons.forEach(function (btn) {
                        btn.addEventListener('click', function () {
                            filterButtons.forEach(function (other) { other.classList.remove('active'); });
                            btn.classList.add('active');
                            if (btn.getAttribute('data-filter')) {
                                activeFilter = btn.getAttribute('data-filter');
                                activeCategory = null;
                            } else if (btn.getAttribute('data-category')) {
                                activeFilter = 'all';
                                activeCategory = btn.getAttribute('data-category');
                            }
                            applyFilters();
                        });
                    });

                    if (searchInput) searchInput.addEventListener('input', applyFilters);
                    if (groupBy) {
                        groupBy.addEventListener('change', function () {
                            groupMode = groupBy.value;
                            rebuildGroups();
                            updateCount();
                            syncHash();
                        });
                    }

                    var reset = document.getElementById('resetFilters');
                    if (reset) {
                        reset.addEventListener('click', function () {
                            if (searchInput) searchInput.value = '';
                            activeFilter = 'all';
                            activeCategory = null;
                            groupMode = 'none';
                            if (groupBy) groupBy.value = 'none';
                            filterButtons.forEach(function (btn) {
                                btn.classList.toggle('active',
                                    btn.getAttribute('data-filter') === 'all');
                            });
                            sortRows('severity', 'asc');
                            applyFilters();
                        });
                    }

                    // --- Export and sharing -------------------------------------
                    function visibleData() {
                        return rows().filter(function (row) { return !row.hidden; }).map(function (row) {
                            return {
                                severity: row.getAttribute('data-severity'),
                                rule: row.getAttribute('data-rule'),
                                file: row.getAttribute('data-path'),
                                line: Number(row.getAttribute('data-line')),
                                message: row.getAttribute('data-message'),
                                remediation: row.getAttribute('data-remediation')
                            };
                        });
                    }

                    function download(name, mime, text) {
                        var blob = new Blob([text], { type: mime });
                        var link = document.createElement('a');
                        link.href = URL.createObjectURL(blob);
                        link.download = name;
                        document.body.appendChild(link);
                        link.click();
                        document.body.removeChild(link);
                        window.setTimeout(function () { URL.revokeObjectURL(link.href); }, 1000);
                    }

                    function csvCell(value) {
                        return '"' + String(value == null ? '' : value).replace(/"/g, '""') + '"';
                    }

                    function toCsv(data) {
                        var lines = ['Severity,Rule,File,Line,Message,Remediation'];
                        data.forEach(function (item) {
                            lines.push([item.severity, item.rule, item.file, item.line,
                                item.message, item.remediation].map(csvCell).join(','));
                        });
                        return lines.join('\\r\\n');
                    }

                    function markdownSummary(data) {
                        var counts = { ERROR: 0, WARNING: 0, INFO: 0 };
                        data.forEach(function (item) { counts[item.severity] = (counts[item.severity] || 0) + 1; });
                        var out = ['## AEM Code Analyser summary', '',
                            '- Findings shown: ' + data.length,
                            '- Errors: ' + counts.ERROR,
                            '- Warnings: ' + counts.WARNING,
                            '- Info: ' + counts.INFO, '',
                            '| Severity | Rule | Location | Issue |', '|---|---|---|---|'];
                        data.slice(0, 50).forEach(function (item) {
                            var issue = String(item.message).split('|').join(' / ');
                            out.push('| ' + item.severity + ' | ' + item.rule + ' | ' + item.file + ':'
                                + item.line + ' | ' + issue + ' |');
                        });
                        if (data.length > 50) {
                            out.push('', '_' + (data.length - 50) + ' more finding(s) omitted._');
                        }
                        return out.join('\\n');
                    }

                    function fallbackCopy(text) {
                        var area = document.createElement('textarea');
                        area.value = text;
                        area.setAttribute('readonly', '');
                        area.style.position = 'fixed';
                        area.style.opacity = '0';
                        document.body.appendChild(area);
                        area.select();
                        try {
                            document.execCommand('copy');
                            showToast('Copied to clipboard');
                        } catch (e) {
                            showToast('Copy failed');
                        }
                        document.body.removeChild(area);
                    }

                    function copyText(text) {
                        if (navigator.clipboard && navigator.clipboard.writeText) {
                            navigator.clipboard.writeText(text).then(function () {
                                showToast('Copied to clipboard');
                            }, function () {
                                fallbackCopy(text);
                            });
                        } else {
                            fallbackCopy(text);
                        }
                    }

                    var exportCsv = document.getElementById('exportCsv');
                    if (exportCsv) {
                        exportCsv.addEventListener('click', function () {
                            download('aem-analyser-findings.csv', 'text/csv', toCsv(visibleData()));
                        });
                    }
                    var exportJson = document.getElementById('exportJson');
                    if (exportJson) {
                        exportJson.addEventListener('click', function () {
                            download('aem-analyser-findings.json', 'application/json',
                                JSON.stringify(visibleData(), null, 2));
                        });
                    }
                    var copyMarkdown = document.getElementById('copyMarkdown');
                    if (copyMarkdown) {
                        copyMarkdown.addEventListener('click', function () {
                            copyText(markdownSummary(visibleData()));
                        });
                    }
                    var copyLink = document.getElementById('copyLink');
                    if (copyLink) {
                        copyLink.addEventListener('click', function () {
                            copyText(window.location.href);
                        });
                    }

                    // --- URL state ----------------------------------------------
                    function syncHash() {
                        var params = new URLSearchParams();
                        if (activeTab !== 'tab-violations') params.set('tab', activeTab);
                        if (searchInput && searchInput.value.trim()) params.set('q', searchInput.value.trim());
                        if (activeFilter !== 'all') params.set('severity', activeFilter);
                        if (activeCategory) params.set('category', activeCategory);
                        if (groupMode !== 'none') params.set('group', groupMode);
                        if (sortKey !== 'severity' || sortDir !== 'asc') {
                            params.set('sort', sortKey + ':' + sortDir);
                        }
                        var next = params.toString();
                        if (window.history && window.history.replaceState) {
                            window.history.replaceState(null, '',
                                next ? '#' + next : window.location.pathname + window.location.search);
                        }
                    }

                    function restoreFromHash() {
                        var hash = window.location.hash || '';
                        if (new RegExp('^#f-[0-9]+$').test(hash)) {
                            activateTab('tab-violations', false);
                            return;
                        }
                        var params = new URLSearchParams(hash.replace(/^#/, ''));
                        var tab = params.get('tab');
                        if (tab) activateTab(tab, false);
                        if (searchInput && params.get('q')) searchInput.value = params.get('q');
                        if (params.get('severity')) activeFilter = params.get('severity');
                        if (params.get('category')) activeCategory = params.get('category');
                        if (params.get('group')) {
                            groupMode = params.get('group');
                            if (groupBy) groupBy.value = groupMode;
                        }
                        var sort = params.get('sort');
                        if (sort) {
                            var parts = sort.split(':');
                            sortKey = parts[0] || 'severity';
                            sortDir = parts[1] === 'desc' ? 'desc' : 'asc';
                        }
                        filterButtons.forEach(function (btn) {
                            var isAll = btn.getAttribute('data-filter') === activeFilter && !activeCategory;
                            var isCategory = activeCategory
                                && btn.getAttribute('data-category') === activeCategory;
                            btn.classList.toggle('active', Boolean(isAll || isCategory));
                        });
                        sortRows(sortKey, sortDir);
                        applyFilters();
                    }

                    updateSortIndicators();
                    restoreFromHash();
                    updateCount();
                })();
                </script>
                """);

        sb.append("</div>\n</body>\n</html>");
        return sb.toString();
    }

    private String classifyDuplicate(Path projectRoot, CodeMetrics.DuplicateBlock block) {
        List<String> lines = readBlock(projectRoot, block);
        String text = String.join("\n", lines).toLowerCase(Locale.ROOT);
        long accessors = lines.stream()
                .map(String::strip)
                .filter(line -> line.matches(
                        "^(public|protected|private|static|final|\\s)*[\\w<>\\[\\], ?.]+\\s+\\w+\\([^)]*\\)\\s*\\{\\s*return\\s+[\\w.$()\"' ]+;\\s*\\}$"))
                .count();
        if (accessors >= Math.max(2, lines.size() / 2)) {
            return "Accessor boilerplate";
        }
        if (text.contains("this(") || text.contains("super(")) {
            return "Constructor delegation";
        }
        if (text.contains("if (") || text.contains("for (") || text.contains("while (")
                || text.contains("switch (") || text.contains("try {")) {
            return "Control-flow logic";
        }
        if (text.contains("@override")) {
            return "Method block";
        }
        return "Statement block";
    }

    private String previewLines(Path projectRoot, CodeMetrics.DuplicateBlock block) {
        List<String> lines = readBlock(projectRoot, block);
        if (lines.isEmpty()) {
            return "(source not available)";
        }
        StringBuilder preview = new StringBuilder();
        int shown = Math.min(8, lines.size());
        for (int i = 0; i < shown; i++) {
            preview.append(String.format("%4d | %s%n", block.duplicateStartLine() + i, lines.get(i)));
        }
        if (lines.size() > shown) {
            preview.append("     | ... ").append(lines.size() - shown).append(" more line(s)");
        }
        return preview.toString();
    }

    private List<String> readBlock(Path projectRoot, CodeMetrics.DuplicateBlock block) {
        try {
            Path file = Path.of(block.duplicateFile());
            if (!file.isAbsolute() && projectRoot != null) {
                file = projectRoot.resolve(block.duplicateFile());
            }
            List<String> all = Files.readAllLines(file);
            int from = Math.max(0, block.duplicateStartLine() - 1);
            int to = Math.min(all.size(), block.duplicateEndLine());
            return from < to ? all.subList(from, to) : List.of();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private long countBySeverity(List<Violation> violations, Severity severity) {
        return violations.stream().filter(violation -> violation.severity() == severity).count();
    }

    private String getRuleCategory(String ruleId) {
        if (ruleId.startsWith("java:S")) return "SONAR";
        if (ruleId.startsWith("AEM-PERF-")) return "PERF";
        if (ruleId.startsWith("AEM-CLOUD-")) return "CLOUD";
        if (ruleId.startsWith("AEM-SEC-")) return "SEC";
        if (ruleId.startsWith("AEM-DEAD-")) return "DEAD";
        if (ruleId.startsWith("AEM-DUP-")) return "DUP";
        if (ruleId.startsWith("AEM-QUAL-")) return "QUAL";
        if (ruleId.startsWith("AEM-COMP-")) return "COMP";
        return "OTHER";
    }

    private String escape(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;")
                .replace("\r", "&#13;")
                .replace("\n", "&#10;")
                .replaceAll("[\\p{Cntrl}&&[^\\x09]]", "");
    }
}
