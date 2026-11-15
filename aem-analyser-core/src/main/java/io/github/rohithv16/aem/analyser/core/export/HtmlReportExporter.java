package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Generates an executive-ready, standalone, offline interactive HTML dashboard.
 * Designed for air-gapped CI/CD artifacts, stakeholder reviews, and local developer exploration.
 */
public final class HtmlReportExporter implements ReportExporter {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

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
        StringBuilder sb = new StringBuilder(16384);

        long errors = report.countBySeverity(Severity.ERROR);
        long warnings = report.countBySeverity(Severity.WARNING);
        long infos = report.countBySeverity(Severity.INFO);
        long totalViolations = report.violations().size();

        String statusBadgeClass = errors > 0 ? "badge-danger" : (warnings > 0 ? "badge-warning" : "badge-success");
        String statusText = errors > 0 ? "FAILED (" + errors + " Errors)" : (warnings > 0 ? "PASSED WITH WARNINGS" : "PASSED");

        List<Violation> sortedViolations = report.violations().stream()
                .sorted(Comparator.comparingInt((Violation v) -> switch (v.severity()) {
                    case ERROR -> 1;
                    case WARNING -> 2;
                    case INFO -> 3;
                }).thenComparing(Violation::filePath)
                        .thenComparingInt(v -> v.region().startLine()))
                .toList();

        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("<title>AEM Code Analyser — Architecture & Quality Report</title>\n");
        sb.append("<style>\n");
        sb.append("""
                :root {
                    --bg-primary: #f8fafc;
                    --bg-card: #ffffff;
                    --text-primary: #0f172a;
                    --text-secondary: #475569;
                    --border: #e2e8f0;
                    --accent: #2563eb;
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
                .status-badge { padding: 0.5rem 1rem; border-radius: 9999px; font-weight: 600; font-size: 0.875rem; text-transform: uppercase; letter-spacing: 0.05em; }
                .badge-danger { background: var(--danger-bg); color: var(--danger); border: 1px solid var(--danger-border); }
                .badge-warning { background: var(--warning-bg); color: var(--warning); border: 1px solid var(--warning-border); }
                .badge-success { background: var(--success-bg); color: var(--success); border: 1px solid var(--success-border); }
                
                .kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 1rem; margin-bottom: 1.5rem; }
                .kpi-card { background: var(--bg-card); padding: 1.25rem; border-radius: 10px; border: 1px solid var(--border); box-shadow: 0 1px 2px rgba(0,0,0,0.04); }
                .kpi-card .label { font-size: 0.75rem; font-weight: 600; text-transform: uppercase; color: var(--text-secondary); letter-spacing: 0.05em; }
                .kpi-card .value { font-size: 1.75rem; font-weight: 700; margin-top: 0.25rem; }
                .kpi-card.err .value { color: var(--danger); }
                .kpi-card.warn .value { color: var(--warning); }
                .kpi-card.info .value { color: var(--info); }
                .kpi-card.cov .value { color: #0d9488; }
                .kpi-card.dup .value { color: #7c3aed; }
                
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
                .rule-code { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-weight: 600; color: #334155; }
                .remediation { margin-top: 0.35rem; font-size: 0.8125rem; color: var(--text-secondary); background: #f1f5f9; padding: 0.4rem 0.6rem; border-radius: 4px; border-left: 3px solid var(--accent); }
                
                .details-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 1rem; padding: 1.5rem; }
                .stat-box { background: #f8fafc; padding: 1rem; border-radius: 8px; border: 1px solid var(--border); }
                .stat-box h4 { font-size: 0.8125rem; font-weight: 600; color: var(--text-secondary); margin-bottom: 0.5rem; text-transform: uppercase; }
                .stat-box ul { list-style: none; }
                .stat-box li { display: flex; justify-content: space-between; padding: 0.25rem 0; font-size: 0.875rem; border-bottom: 1px dashed var(--border); }
                .stat-box li:last-child { border-bottom: none; }
                
                footer { text-align: center; color: var(--text-secondary); font-size: 0.8125rem; margin-top: 2rem; }
                """);
        sb.append("</style>\n</head>\n<body>\n<div class=\"container\">\n");

        // Header
        sb.append("<header>\n");
        sb.append("  <div class=\"header-title\">\n");
        sb.append("    <h1>⚡ AEM Code Analyser</h1>\n");
        sb.append("    <p>Project Root: <code>").append(escape(report.projectRoot().toString())).append("</code> &bull; Generated: ")
                .append(ISO_FORMATTER.format(Instant.now())).append(" &bull; Duration: ")
                .append(String.format("%.2fs", report.durationMillis() / 1000.0)).append("</p>\n");
        sb.append("  </div>\n");
        sb.append("  <div><span class=\"status-badge ").append(statusBadgeClass).append("\">").append(statusText).append("</span></div>\n");
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
            sb.append("  <div class=\"kpi-card\">\n    <div class=\"label\">Test Coverage</div>\n    <div class=\"value\" style=\"font-size:1.1rem;color:var(--text-secondary);margin-top:0.5rem;\">N/A</div>\n    <div style=\"font-size:0.75rem;color:var(--text-secondary);margin-top:2px;\">Run mvn test first</div>\n  </div>\n");
        }
        sb.append("</div>\n");

        // Violations Section
        sb.append("<div class=\"section\">\n");
        sb.append("  <div class=\"section-header\">\n");
        sb.append("    <h2>Violations (").append(totalViolations).append(")</h2>\n");
        sb.append("  </div>\n");

        // Controls
        sb.append("  <div class=\"controls\">\n");
        sb.append("    <input type=\"text\" id=\"searchInput\" class=\"search-box\" placeholder=\"Search by file, rule, message, or keyword...\">\n");
        sb.append("    <button class=\"filter-btn active\" data-filter=\"all\">All</button>\n");
        sb.append("    <button class=\"filter-btn\" data-filter=\"ERROR\">Errors Only</button>\n");
        sb.append("    <button class=\"filter-btn\" data-filter=\"WARNING\">Warnings Only</button>\n");
        sb.append("    <button class=\"filter-btn\" data-filter=\"INFO\">Info Only</button>\n");
        sb.append("    <button class=\"filter-btn\" data-category=\"PERF\">Session Leaks</button>\n");
        sb.append("    <button class=\"filter-btn\" data-category=\"CLOUD\">Cloud Service</button>\n");
        sb.append("    <button class=\"filter-btn\" data-category=\"SEC\">Security</button>\n");
        sb.append("    <button class=\"filter-btn\" data-category=\"DEAD\">Dead Code</button>\n");
        sb.append("    <button class=\"filter-btn\" data-category=\"DUP\">Duplicates</button>\n");
        sb.append("  </div>\n");

        // Table
        sb.append("  <div style=\"overflow-x:auto;\">\n");
        sb.append("    <table id=\"violationsTable\">\n");
        sb.append("      <thead>\n        <tr>\n");
        sb.append("          <th style=\"width:100px;\">Severity</th>\n");
        sb.append("          <th style=\"width:140px;\">Rule</th>\n");
        sb.append("          <th>File Location</th>\n");
        sb.append("          <th>Issue & Remediation</th>\n");
        sb.append("        </tr>\n      </thead>\n      <tbody>\n");

        if (sortedViolations.isEmpty()) {
            sb.append("        <tr><td colspan=\"4\" style=\"text-align:center;padding:2rem;color:var(--success);font-weight:600;\">🎉 Clean build! No architectural or quality violations detected.</td></tr>\n");
        } else {
            for (Violation v : sortedViolations) {
                String pillClass = switch (v.severity()) {
                    case ERROR -> "pill-error";
                    case WARNING -> "pill-warning";
                    case INFO -> "pill-info";
                };
                String cat = getRuleCategory(v.ruleId());

                sb.append("        <tr class=\"violation-row\" data-severity=\"").append(v.severity().name())
                        .append("\" data-category=\"").append(cat).append("\">\n");
                sb.append("          <td><span class=\"pill ").append(pillClass).append("\">").append(v.severity()).append("</span></td>\n");
                sb.append("          <td><span class=\"rule-code\">").append(escape(v.ruleId())).append("</span></td>\n");
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
        }

        sb.append("      </tbody>\n    </table>\n  </div>\n</div>\n");

        // Code Duplication Table
        if (!dupMetrics.duplicateClasses().isEmpty()) {
            sb.append("<div class=\"section\">\n");
            sb.append("  <div class=\"section-header\">\n");
            sb.append("    <h2>Duplication Breakdown by Class (").append(dupMetrics.duplicateClasses().size()).append(" Classes)</h2>\n");
            sb.append("  </div>\n");
            sb.append("  <div style=\"overflow-x:auto;\">\n");
            sb.append("    <table>\n      <thead>\n        <tr>\n");
            sb.append("          <th>Class Name</th>\n");
            sb.append("          <th style=\"width:180px;\">Duplicate Lines</th>\n");
            sb.append("          <th style=\"width:180px;\">Class Duplication %</th>\n");
            sb.append("        </tr>\n      </thead>\n      <tbody>\n");
            for (var dupClass : dupMetrics.duplicateClasses()) {
                sb.append("        <tr>\n");
                sb.append("          <td><code>").append(escape(dupClass.className())).append("</code></td>\n");
                sb.append("          <td><strong>").append(dupClass.duplicatedLines()).append("</strong> lines</td>\n");
                sb.append("          <td>").append(String.format("%.1f%%", dupClass.duplicationPercent())).append("</td>\n");
                sb.append("        </tr>\n");
            }
            sb.append("      </tbody>\n    </table>\n  </div>\n</div>\n");
        }

        // Architecture & Scan Metrics
        sb.append("<div class=\"section\">\n");
        sb.append("  <div class=\"section-header\">\n    <h2>Scan Statistics & Architecture Topology</h2>\n  </div>\n");
        sb.append("  <div class=\"details-grid\">\n");

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

        if (cov != null && cov.present()) {
            sb.append("    <div class=\"stat-box\">\n      <h4>Test Coverage Metrics</h4>\n      <ul>\n");
            sb.append("        <li><span>Line Coverage</span><strong>").append(String.format("%.1f%%", cov.lineCoveragePercent())).append("</strong></li>\n");
            sb.append("        <li><span>Covered / Total Lines</span><strong>").append(cov.coveredLines()).append(" / ").append(cov.totalLines()).append("</strong></li>\n");
            sb.append("        <li><span>Branch Coverage</span><strong>").append(String.format("%.1f%%", cov.branchCoveragePercent())).append("</strong></li>\n");
            sb.append("        <li><span>Covered / Total Branches</span><strong>").append(cov.coveredBranches()).append(" / ").append(cov.totalBranches()).append("</strong></li>\n");
            if (cov.sourcePath() != null) {
                sb.append("        <li><span>Coverage Source</span><strong style=\"font-size:0.75rem;\">").append(escape(cov.sourcePath())).append("</strong></li>\n");
            }
            sb.append("      </ul>\n    </div>\n");
        }

        sb.append("  </div>\n</div>\n");

        // Footer
        sb.append("<footer>\n");
        sb.append("  <p>Generated by AEM Code Analyser &bull; Zero-config Inner-loop Engine &bull; Strictly <code>io.github.rohithv16</code></p>\n");
        sb.append("</footer>\n");

        // Interactive JavaScript
        sb.append("""
                <script>
                (function() {
                    const searchInput = document.getElementById('searchInput');
                    const filterButtons = document.querySelectorAll('.filter-btn');
                    const rows = document.querySelectorAll('.violation-row');
                    let activeFilter = 'all';
                    let activeCategory = null;

                    function filterTable() {
                        const query = searchInput.value.toLowerCase().trim();
                        rows.forEach(row => {
                            const sev = row.getAttribute('data-severity');
                            const cat = row.getAttribute('data-category');
                            const text = row.innerText.toLowerCase();

                            let matchesFilter = true;
                            if (activeFilter !== 'all') {
                                matchesFilter = (sev === activeFilter);
                            }
                            if (activeCategory) {
                                matchesFilter = matchesFilter && (cat === activeCategory);
                            }

                            const matchesSearch = !query || text.includes(query);
                            row.style.display = (matchesFilter && matchesSearch) ? '' : 'none';
                        });
                    }

                    if (searchInput) {
                        searchInput.addEventListener('input', filterTable);
                    }

                    filterButtons.forEach(btn => {
                        btn.addEventListener('click', function() {
                            filterButtons.forEach(b => b.classList.remove('active'));
                            this.classList.add('active');

                            if (this.dataset.filter) {
                                activeFilter = this.dataset.filter;
                                activeCategory = null;
                            } else if (this.dataset.category) {
                                activeFilter = 'all';
                                activeCategory = this.dataset.category;
                            }
                            filterTable();
                        });
                    });
                })();
                </script>
                """);

        sb.append("</div>\n</body>\n</html>");
        return sb.toString();
    }

    private String getRuleCategory(String ruleId) {
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
                .replace("'", "&#x27;");
    }
}
