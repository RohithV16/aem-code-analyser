package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.history.RunSummary;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.CodeMetrics;
import io.github.rohithv16.aem.analyser.core.model.report.CoverageReport;
import io.github.rohithv16.aem.analyser.core.model.report.GateVerdict;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates a standalone, offline, interactive HTML dashboard.
 *
 * <p>It is a monitoring surface, not a landing page: the verdict, the counts and the findings are
 * above the fold. Every rule id links to its explanation and fix on the published issue reference,
 * so a reader never has to leave the report to find out what a finding means.</p>
 */
public final class HtmlReportExporter implements ReportExporter {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT).withZone(ZoneId.systemDefault());

    /** Family chips, in the order the site lists them: code and label. */
    private static final String[][] FAMILY_CHIPS = {
            {"DEAD", "Dead code"}, {"DUP", "Duplicates"}, {"QUAL", "Quality"}, {"PERF", "Performance"},
            {"CLOUD", "Cloud service"}, {"SEC", "Security"}, {"COMP", "Classic UI"}, {"SONAR", "SonarJava"},
    };

    /** Anchor target on the published site; kept in step with the generated issue reference. */
    private static final String RULE_DOCS = "https://rohithv16.github.io/aem-code-analyser/issues/#";

    private final java.time.Clock clock;
    private final int maxViolations;
    private final List<RunSummary> history;
    /** True for a reproducible run: the timestamp comes from a fixed clock and the duration is dropped. */
    private final boolean omitDuration;

    /** Canonical URL of the published report, or null when the caller did not supply one. */
    private final String siteUrl;

    public HtmlReportExporter() {
        this(java.time.Clock.systemDefaultZone(), 5000, List.of(), false);
    }

    public HtmlReportExporter(java.time.Clock clock) {
        this(clock, 5000, List.of(), false);
    }

    public HtmlReportExporter(java.time.Clock clock, int maxViolations) {
        this(clock, maxViolations, List.of(), false);
    }

    public HtmlReportExporter(java.time.Clock clock, int maxViolations, List<RunSummary> history) {
        this(clock, maxViolations, history, false, configuredSiteUrl());
    }

    /**
     * @param omitDuration report a fixed report clock was installed ({@code --report-epoch}): the
     *                     wall-clock duration and the local timezone are left out, so the same tree
     *                     yields identical bytes on any machine
     */
    public HtmlReportExporter(java.time.Clock clock, int maxViolations, List<RunSummary> history,
                              boolean omitDuration) {
        this(clock, maxViolations, history, omitDuration, configuredSiteUrl());
    }

    /**
     * @param siteUrl absolute URL the report will be published at, for the canonical link; null when
     *                the report has no public home, in which case only {@code noindex} is emitted
     */
    public HtmlReportExporter(java.time.Clock clock, int maxViolations, List<RunSummary> history,
                              String siteUrl) {
        this(clock, maxViolations, history, false, siteUrl);
    }

    /**
     * Canonical constructor: a reproducible run ({@code --report-epoch}) and a published location
     * ({@code -Daem.analyser.siteUrl}) are independent, so both are settable at once.
     */
    public HtmlReportExporter(java.time.Clock clock, int maxViolations, List<RunSummary> history,
                              boolean omitDuration, String siteUrl) {
        this.clock = clock == null ? java.time.Clock.systemDefaultZone() : clock;
        this.maxViolations = Math.max(1, maxViolations);
        this.history = List.copyOf(history == null ? List.of() : history);
        this.omitDuration = omitDuration;
        this.siteUrl = normaliseSiteUrl(siteUrl);
    }

    /**
     * The published location of the report, when a build knows it. A report is normally a build
     * artefact with no URL, so the default is "none" and only {@code noindex} is emitted; a pipeline
     * that does publish one (the project's own demo page) sets
     * {@code -Daem.analyser.siteUrl=https://host/path/}.
     */
    private static String configuredSiteUrl() {
        String value = System.getProperty("aem.analyser.siteUrl");
        if (value == null || value.isBlank()) {
            value = System.getenv("AEM_ANALYSER_SITE_URL");
        }
        return value;
    }

    /** Only an absolute http(s) URL can be a canonical, and a trailing slash is part of the URL. */
    private static String normaliseSiteUrl(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return null;
        }
        return trimmed;
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
        // Baseline-accepted findings are listed in Run Health instead of being mixed into the active
        // list, so totals and the gate badge reflect what still needs fixing.
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

        GateVerdict verdict = report.gateVerdict();
        String verdictClass = switch (verdict.status()) {
            case FAILED -> "verdict--fail";
            case PASSED -> (!report.diagnostics().isEmpty() || warnings > 0) ? "verdict--warn" : "verdict--pass";
            default -> "verdict--warn";
        };
        String statusText = switch (verdict.status()) {
            case FAILED -> "QUALITY GATE FAILED — " + verdict.reason();
            // Report-only run (--fail-on NONE): findings exist but nobody asked the build to fail,
            // so the header must not claim a failure the exit code does not signal.
            case NOT_ENFORCED -> "GATE NOT ENFORCED — report only ("
                    + errors + " Errors, " + warnings + " Warnings)";
            case PASSED_WITH_WARNINGS -> !report.diagnostics().isEmpty()
                    ? "PASSED WITH " + diagnosticsCount + " DIAGNOSTIC(S)"
                    : "PASSED WITH WARNINGS";
            case PASSED -> !report.diagnostics().isEmpty()
                    ? "PASSED WITH " + diagnosticsCount + " DIAGNOSTIC(S)"
                    : "QUALITY GATE PASSED";
        };

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

        CodeMetrics metrics = report.codeMetrics();
        CoverageReport coverage = report.coverageReport();

        String generatedAt = generatedAt();
        StringBuilder sb = new StringBuilder(96_000);
        sb.append("<!DOCTYPE html>\n<html lang=\"en\" data-theme=\"light\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("<meta name=\"generator\" content=\"AEM Code Analyser v")
                .append(escape(VersionInfo.current())).append("\">\n");
        String title = "AEM Code Analyser — " + totalViolations
                + (totalViolations == 1 ? " finding" : " findings");
        String description = metaDescription(report, totalViolations, errors, warnings, infos);
        sb.append("<title>").append(escape(title)).append("</title>\n");
        // The report is an artefact people forward into tickets, PR comments and wikis. A preview
        // with a title and a description is the difference between a useful unfurl and a bare link,
        // and noindex keeps a copy pasted onto a Pages site out of the search index as thin content.
        sb.append("<meta name=\"robots\" content=\"noindex\">\n");
        sb.append("<meta name=\"description\" content=\"").append(escape(description)).append("\">\n");
        sb.append("<meta property=\"og:type\" content=\"website\">\n");
        sb.append("<meta property=\"og:site_name\" content=\"AEM Code Analyser\">\n");
        sb.append("<meta property=\"og:title\" content=\"").append(escape(title)).append("\">\n");
        sb.append("<meta property=\"og:description\" content=\"").append(escape(description)).append("\">\n");
        sb.append("<meta name=\"twitter:card\" content=\"summary\">\n");
        sb.append("<meta name=\"twitter:title\" content=\"").append(escape(title)).append("\">\n");
        sb.append("<meta name=\"twitter:description\" content=\"").append(escape(description)).append("\">\n");
        if (siteUrl != null) {
            sb.append("<link rel=\"canonical\" href=\"").append(escape(siteUrl)).append("\">\n");
            sb.append("<meta property=\"og:url\" content=\"").append(escape(siteUrl)).append("\">\n");
        }
        sb.append("<script>(function(){try{var t=localStorage.getItem('aem-analyser-theme')"
                + "||(window.matchMedia&&window.matchMedia('(prefers-color-scheme: dark)').matches"
                + "?'dark':'light');document.documentElement.setAttribute('data-theme',t);"
                + "}catch(e){}})();</script>\n");
        sb.append("<style>\n").append(STYLES).append("</style>\n</head>\n<body>\n");

        sb.append("<a class=\"skip\" href=\"#main\">Skip to findings</a>\n");
        sb.append("<noscript><style>.tab-panel{display:block !important} .tabbar{display:none !important}</style>")
                .append("<p class=\"noscript\">JavaScript is disabled: every section is shown expanded, ")
                .append("but search, filters and exports are unavailable.</p></noscript>\n");

        header(sb, report, statusText, verdictClass, generatedAt);
        sb.append("<main id=\"main\" tabindex=\"-1\">\n");
        kpiStrip(sb, totalViolations, errors, warnings, infos, acceptedViolations.size(), metrics, coverage);
        tabs(sb, totalViolations, diagnosticsCount + acceptedViolations.size());

        violationsPanel(sb, sortedViolations, totalViolations, categoryCounts);
        overviewPanel(sb, activeViolations, totalViolations, errors, warnings, infos, report, metrics);
        healthPanel(sb, report, acceptedViolations);
        duplicationPanel(sb, report, metrics);
        coveragePanel(sb, coverage);
        autoFixPanel(sb);

        sb.append("</main>\n");
        sb.append("<footer class=\"foot\">\n")
                .append("  <span>Generated by AEM Code Analyser <span class=\"mono\">v")
                .append(escape(VersionInfo.current())).append("</span></span>\n")
                .append("  <span>Rule explanations and fixes: <a href=\"").append(escape(RULE_DOCS))
                .append("\" target=\"_blank\" rel=\"noopener\">issue reference</a></span>\n")
                .append("  <span class=\"mono\">").append(escape(generatedAt))
                .append("</span>\n")
                .append("</footer>\n");
        sb.append("<div id=\"toast\" role=\"status\" aria-live=\"polite\"></div>\n");

        sb.append("<script>\n").append(SCRIPT).append("</script>\n");
        sb.append("</body>\n</html>");
        return sb.toString();
    }

    // ------------------------------------------------------------------ chrome

    private void header(StringBuilder sb, AnalysisReport report, String statusText, String verdictClass,
                        String generatedAt) {
        sb.append("<header class=\"top\">\n  <div class=\"top__inner\">\n");
        sb.append("    <div class=\"brand\">\n")
                .append("      <span class=\"brand__mark\" aria-hidden=\"true\">AEM</span>\n")
                .append("      <h1 class=\"brand__title\">AEM Code Analyser</h1>\n")
                .append("      <span class=\"brand__version mono\">v").append(escape(VersionInfo.current()))
                .append("</span>\n    </div>\n");
        sb.append("    <div class=\"top__actions\">\n")
                .append("      <span class=\"verdict ").append(verdictClass).append("\">")
                .append(escape(statusText)).append("</span>\n")
                .append("      <button id=\"themeToggle\" class=\"icon\" type=\"button\" ")
                .append("aria-label=\"Toggle dark mode\" aria-pressed=\"false\">")
                .append("<svg viewBox=\"0 0 16 16\" width=\"15\" height=\"15\" fill=\"none\" stroke=\"currentColor\" ")
                .append("stroke-width=\"1.4\" aria-hidden=\"true\">")
                .append("<path d=\"M13.2 9.6A5.6 5.6 0 0 1 6.4 2.8a5.6 5.6 0 1 0 6.8 6.8Z\" ")
                .append("stroke-linejoin=\"round\"/></svg></button>\n")
                .append("    </div>\n");
        sb.append("  </div>\n  <div class=\"top__meta\">\n")
                .append("    <span class=\"mono\">").append(escape(displayRoot(report.projectRoot()))).append("</span>")
                .append("<span>Generated ").append(escape(generatedAt)).append("</span>");
        if (!omitDuration) {
            sb.append("<span>Analysis ")
                    .append(String.format(Locale.ROOT, "%.2fs", report.durationMillis() / 1000.0)).append("</span>");
        }
        if (!report.scanStatistics().isEmpty()) {
            sb.append("<span>").append(plural(report.scanStatistics().size(), "scan metric", "scan metrics"))
                    .append("</span>");
        }
        sb.append("\n  </div>\n</header>\n");
    }

    private void kpiStrip(StringBuilder sb, long total, long errors, long warnings, long infos,
                          int accepted, CodeMetrics metrics, CoverageReport coverage) {
        sb.append("<section class=\"kpis\" aria-label=\"Summary\">\n");
        kpi(sb, "Findings", Long.toString(total), "active, baseline excluded", "kpi--total");
        kpi(sb, "Errors", Long.toString(errors), errors > 0 ? "fail the gate" : "none", "kpi--error");
        kpi(sb, "Warnings", Long.toString(warnings), warnings > 0 ? "need review" : "none", "kpi--warn");
        kpi(sb, "Info", Long.toString(infos), "advisory", "kpi--info");
        if (accepted > 0) {
            kpi(sb, "Accepted", Integer.toString(accepted), "baselined, not counted", "kpi--muted");
        }
        kpi(sb, "Duplication", String.format(Locale.ROOT, "%.1f%%", metrics.duplicationPercent()),
                String.format(Locale.ROOT, "%,d lines · %s",
                        metrics.duplicatedLines(), plural(metrics.totalMethodBlocks(), "block", "blocks")), "kpi--muted");
        if (coverage != null && coverage.present()) {
            kpi(sb, "Coverage", String.format(Locale.ROOT, "%.1f%%", coverage.lineCoveragePercent()),
                    String.format(Locale.ROOT, "%,d / %,d lines", coverage.coveredLines(), coverage.totalLines()),
                    "kpi--muted");
        }
        // No coverage data: leave the card out rather than spend a sixth of the summary on an
        // empty value; the Coverage tab explains how to produce one.
        sb.append("</section>\n");
    }

    private void kpi(StringBuilder sb, String label, String value, String note, String modifier) {
        sb.append("  <div class=\"kpi ").append(modifier).append("\">\n")
                .append("    <div class=\"kpi__label\">").append(escape(label)).append("</div>\n")
                .append("    <div class=\"kpi__value\">").append(escape(value)).append("</div>\n")
                .append("    <div class=\"kpi__note\">").append(escape(note)).append("</div>\n")
                .append("  </div>\n");
    }

    private void tabs(StringBuilder sb, long totalViolations, int healthCount) {
        sb.append("<nav class=\"tabbar\" role=\"tablist\" aria-label=\"Report sections\">\n");
        tab(sb, "tab-violations", "Violations Explorer (" + totalViolations + ")", true);
        tab(sb, "tab-overview", "Overview", false);
        tab(sb, "tab-health", "Run Health (" + healthCount + ")", false);
        tab(sb, "tab-duplication", "Duplication", false);
        tab(sb, "tab-coverage", "Coverage", false);
        tab(sb, "tab-autofix", "Auto-fix", false);
        sb.append("</nav>\n");
    }

    private void tab(StringBuilder sb, String id, String label, boolean active) {
        sb.append("  <button class=\"tab").append(active ? " is-active" : "")
                .append("\" id=\"tabbtn-").append(id.substring(4)).append("\" role=\"tab\" data-tab=\"")
                .append(id).append("\" aria-controls=\"").append(id).append("\" aria-selected=\"")
                .append(active).append("\" tabindex=\"").append(active ? "0" : "-1").append("\">")
                .append(escape(label)).append("</button>\n");
    }

    // --------------------------------------------------------------- findings

    private void violationsPanel(StringBuilder sb, List<Violation> violations, long total,
                                 Map<String, Long> categoryCounts) {
        sb.append("<section id=\"tab-violations\" class=\"tab-panel is-active\" role=\"tabpanel\" ")
                .append("aria-labelledby=\"tabbtn-violations\" tabindex=\"0\">\n");

        sb.append("  <div class=\"panel\">\n");
        sb.append("    <div class=\"panel__head\">\n      <h2>Active findings <span class=\"count\">")
                .append(total).append("</span></h2>\n")
                .append("      <p class=\"panel__hint\">Every rule links to its explanation and fix.</p>\n")
                .append("    </div>\n");

        sb.append("    <div class=\"toolbar\" role=\"toolbar\" aria-label=\"Finding filters\">\n");
        sb.append("      <input type=\"search\" id=\"searchInput\" class=\"search\" ")
                .append("placeholder=\"Search file, rule, message or keyword\" aria-label=\"Search findings\">\n");
        sb.append("      <div class=\"chips\" role=\"group\" aria-label=\"Severity\">\n");
        sb.append("        <button class=\"chip is-active\" type=\"button\" data-filter=\"all\">All</button>\n");
        sb.append("        <button class=\"chip\" type=\"button\" data-filter=\"ERROR\">Errors</button>\n");
        sb.append("        <button class=\"chip\" type=\"button\" data-filter=\"WARNING\">Warnings</button>\n");
        sb.append("        <button class=\"chip\" type=\"button\" data-filter=\"INFO\">Info</button>\n");
        sb.append("      </div>\n");
        sb.append("      <div class=\"chips\" role=\"group\" aria-label=\"Family\">\n");
        for (String[] family : FAMILY_CHIPS) {
            familyChip(sb, family[0], family[1], categoryCounts);
        }
        sb.append("      </div>\n");
        sb.append("      <label class=\"field\" for=\"groupBy\">Group\n")
                .append("        <select id=\"groupBy\">\n")
                .append("          <option value=\"none\">None</option>\n")
                .append("          <option value=\"rule\">Rule</option>\n")
                .append("          <option value=\"file\">File</option>\n")
                .append("        </select>\n      </label>\n");
        sb.append("      <div class=\"tools\">\n")
                .append("        <button id=\"resetFilters\" class=\"btn\" type=\"button\">Reset</button>\n")
                .append("        <button id=\"exportCsv\" class=\"btn\" type=\"button\">CSV</button>\n")
                .append("        <button id=\"exportJson\" class=\"btn\" type=\"button\">JSON</button>\n")
                .append("        <button id=\"copyMarkdown\" class=\"btn\" type=\"button\">Copy summary</button>\n")
                .append("        <button id=\"copyLink\" class=\"btn\" type=\"button\">Copy link</button>\n")
                .append("      </div>\n");
        sb.append("      <span id=\"resultCount\" class=\"result-count\" aria-live=\"polite\"></span>\n");
        sb.append("    </div>\n");

        sb.append("    <div class=\"table-scroll\">\n    <table id=\"violationsTable\">\n      <thead>\n")
                .append("        <tr>\n")
                .append("          <th scope=\"col\" class=\"sortable sev\" data-sort=\"severity\" tabindex=\"0\">")
                .append("Severity <span class=\"sort-indicator\"></span></th>\n")
                .append("          <th scope=\"col\" class=\"sortable rule\" data-sort=\"rule\" tabindex=\"0\">")
                .append("Rule <span class=\"sort-indicator\"></span></th>\n")
                .append("          <th scope=\"col\" class=\"sortable\" data-sort=\"path\" tabindex=\"0\">")
                .append("Location <span class=\"sort-indicator\"></span></th>\n")
                .append("          <th scope=\"col\">Finding and fix</th>\n")
                .append("        </tr>\n      </thead>\n      <tbody>\n");

        if (violations.isEmpty()) {
            sb.append("        <tr class=\"empty-row\"><td colspan=\"4\">\n")
                    .append("          <div class=\"empty\"><span class=\"empty__mark\">✓</span>")
                    .append("<div><strong>No active findings.</strong> Nothing to fix in this run.</div></div>\n")
                    .append("        </td></tr>\n");
        } else {
            int index = 0;
            for (Violation v : violations) {
                if (index >= maxViolations) {
                    break;
                }
                index++;
                renderViolationRow(sb, v, index);
            }
            if (violations.size() > maxViolations) {
                sb.append("        <tr class=\"omitted-row\"><td colspan=\"4\">")
                        .append(violations.size() - maxViolations)
                        .append(" more violation(s) omitted from this dashboard; use --format SARIF or SONAR ")
                        .append("for the full set.</td></tr>\n");
            }
        }
        sb.append("      </tbody>\n    </table>\n    </div>\n  </div>\n</section>\n");
    }

    private void familyChip(StringBuilder sb, String family, String label, Map<String, Long> counts) {
        long count = counts.getOrDefault(family, 0L);
        if (count == 0) {
            return;
        }
        sb.append("        <button class=\"chip\" type=\"button\" data-category=\"")
                .append(family).append("\">").append(escape(label)).append(" <span>")
                .append(count).append("</span></button>\n");
    }

    private void renderViolationRow(StringBuilder sb, Violation v, int index) {
        String severityClass = switch (v.severity()) {
            case ERROR -> "pill--error";
            case WARNING -> "pill--warning";
            case INFO -> "pill--info";
        };
        String helpUri = escape(helpUriFor(v.ruleId()));

        sb.append("        <tr class=\"violation-row\" id=\"f-").append(index).append("\"")
                .append(" data-severity=\"").append(v.severity().name()).append("\"")
                .append(" data-category=\"").append(getRuleCategory(v.ruleId())).append("\"")
                .append(" data-rule=\"").append(escape(v.ruleId())).append("\"")
                .append(" data-path=\"").append(escape(v.filePath())).append("\"")
                .append(" data-line=\"").append(v.region().startLine()).append("\"")
                .append(" data-message=\"").append(escape(v.message())).append("\"")
                .append(" data-remediation=\"").append(escape(v.remediationOpt().orElse(""))).append("\">\n");

        sb.append("          <td data-label=\"Severity\"><span class=\"pill ").append(severityClass)
                .append("\">").append(v.severity()).append("</span></td>\n");

        sb.append("          <td data-label=\"Rule\"><a class=\"rule-code\" href=\"").append(helpUri)
                .append("\" target=\"_blank\" rel=\"noopener\" title=\"What ").append(escape(v.ruleId()))
                .append(" means and how to fix it\">").append(escape(v.ruleId())).append(" ↗</a>")
                .append("<a class=\"permalink\" href=\"#f-").append(index)
                .append("\" title=\"Link to this finding\" aria-label=\"Link to this finding\">#</a></td>\n");

        sb.append("          <td data-label=\"Location\"><span class=\"loc\">").append(breakablePath(v.filePath()))
                .append(":").append(v.region().startLine()).append("</span></td>\n");

        sb.append("          <td data-label=\"Finding\">\n")
                .append("            <p class=\"finding\">").append(escape(v.message())).append("</p>\n");
        v.remediationOpt().ifPresent(rem -> sb.append("            <p class=\"fix\"><strong>Fix</strong>")
                .append(escape(rem)).append("</p>\n"));
        if (!v.relatedLocations().isEmpty()) {
            sb.append("            <p class=\"related\">Related: ");
            v.relatedLocations().forEach(rel -> sb.append("<span class=\"loc\">")
                    .append(escape(rel.filePath())).append(":").append(rel.region().startLine())
                    .append("</span> "));
            sb.append("</p>\n");
        }
        sb.append("            <a class=\"fix-link\" href=\"").append(helpUri)
                .append("\" target=\"_blank\" rel=\"noopener\">Read the fix for ")
                .append(escape(v.ruleId())).append(" ↗</a>\n");
        sb.append("          </td>\n");
        sb.append("        </tr>\n");
    }

    // --------------------------------------------------------------- overview

    private void overviewPanel(StringBuilder sb, List<Violation> violations, long total,
                               long errors, long warnings, long infos,
                               AnalysisReport report, CodeMetrics metrics) {
        sb.append("<section id=\"tab-overview\" class=\"tab-panel\" role=\"tabpanel\" ")
                .append("aria-labelledby=\"tabbtn-overview\" tabindex=\"0\">\n");

        // Rules in this report: the fastest path from "what is failing" to "how do I fix it".
        sb.append("  <div class=\"panel\">\n    <div class=\"panel__head\">\n")
                .append("      <h2>Rules in this report</h2>\n")
                .append("      <p class=\"panel__hint\">Ordered by number of findings.</p>\n    </div>\n");
        sb.append("    <div class=\"table-scroll\"><table class=\"compact\">\n")
                .append("      <thead><tr><th scope=\"col\">Rule</th><th scope=\"col\">Findings</th>")
                .append("<th scope=\"col\">Worst severity</th><th scope=\"col\">Fix</th></tr></thead>\n")
                .append("      <tbody>\n");
        if (violations.isEmpty()) {
            sb.append("        <tr><td colspan=\"4\" class=\"muted\">No rules reported findings.</td></tr>\n");
        } else {
            for (Map.Entry<String, List<Violation>> entry : byRule(violations).entrySet()) {
                String ruleId = entry.getKey();
                Severity worst = entry.getValue().stream()
                        .map(Violation::severity)
                        .min(Comparator.comparingInt(HtmlReportExporter::severityRank))
                        .orElse(Severity.INFO);
                String helpUri = escape(helpUriFor(ruleId));
                sb.append("        <tr><td><a class=\"rule-code\" href=\"").append(helpUri)
                        .append("\" target=\"_blank\" rel=\"noopener\">").append(escape(ruleId)).append("</a></td>")
                        .append("<td class=\"num\">").append(entry.getValue().size()).append("</td>")
                        .append("<td><span class=\"pill ").append(switch (worst) {
                            case ERROR -> "pill--error";
                            case WARNING -> "pill--warning";
                            case INFO -> "pill--info";
                        }).append("\">").append(worst).append("</span></td>")
                        .append("<td><a href=\"").append(helpUri)
                        .append("\" target=\"_blank\" rel=\"noopener\">Open fix guide ↗</a></td></tr>\n");
            }
        }
        sb.append("      </tbody>\n    </table></div>\n  </div>\n");

        sb.append("  <div class=\"panel\">\n    <div class=\"panel__head\"><h2>Distribution and scan facts</h2></div>\n")
                .append("    <div class=\"grid\">\n");

        sb.append("      <div class=\"stat\">\n        <h3>Severity</h3>\n");
        if (total > 0) {
            sb.append("        <div class=\"bar\" role=\"img\" aria-label=\"Severity distribution\">");
            if (errors > 0) {
                sb.append("<span class=\"bar--error\" style=\"width:").append(percent(errors, total))
                        .append("%\"></span>");
            }
            if (warnings > 0) {
                sb.append("<span class=\"bar--warn\" style=\"width:").append(percent(warnings, total))
                        .append("%\"></span>");
            }
            if (infos > 0) {
                sb.append("<span class=\"bar--info\" style=\"width:").append(percent(infos, total))
                        .append("%\"></span>");
            }
            sb.append("</div>\n");
        }
        sb.append("        <ul class=\"kv\">\n")
                .append("          <li><span>Errors</span><strong class=\"t-error\">").append(errors)
                .append("</strong></li>\n")
                .append("          <li><span>Warnings</span><strong class=\"t-warn\">").append(warnings)
                .append("</strong></li>\n")
                .append("          <li><span>Info</span><strong class=\"t-info\">").append(infos)
                .append("</strong></li>\n")
                .append("        </ul>\n      </div>\n");

        sb.append("      <div class=\"stat\">\n        <h3>Repository</h3>\n        <ul class=\"kv\">\n");
        if (report.scanStatistics().isEmpty()) {
            sb.append("          <li><span>Scan statistics</span><strong>—</strong></li>\n");
        } else {
            report.scanStatistics().forEach((key, value) -> sb.append("          <li><span>")
                    .append(escape(key)).append("</span><strong>").append(escape(String.valueOf(value)))
                    .append("</strong></li>\n"));
        }
        sb.append("        </ul>\n      </div>\n");

        sb.append("      <div class=\"stat\">\n        <h3>Code metrics</h3>\n        <ul class=\"kv\">\n")
                .append("          <li><span>Java classes</span><strong>").append(metrics.totalJavaFiles())
                .append("</strong></li>\n")
                .append("          <li><span>Lines of code</span><strong>").append(metrics.totalJavaLines())
                .append("</strong></li>\n")
                .append("          <li><span>Duplicate blocks</span><strong>").append(metrics.totalMethodBlocks())
                .append("</strong></li>\n")
                .append("          <li><span>Duplicated lines</span><strong>").append(metrics.duplicatedLines())
                .append("</strong></li>\n")
                .append("        </ul>\n      </div>\n");

        sb.append("    </div>\n  </div>\n");

        if (!history.isEmpty()) {
            sb.append("  <div class=\"panel\">\n    <div class=\"panel__head\"><h2>Run history <span class=\"count\">")
                    .append(history.size()).append("</span></h2></div>\n")
                    .append("    <div class=\"table-scroll\"><table class=\"compact\">\n")
                    .append("      <thead><tr><th scope=\"col\">Run</th><th scope=\"col\">Findings</th>")
                    .append("<th scope=\"col\">Errors</th><th scope=\"col\">Warnings</th><th scope=\"col\">Info</th>")
                    .append("<th scope=\"col\">Duplication</th><th scope=\"col\">Duration</th></tr></thead>\n")
                    .append("      <tbody>\n");
            int run = 1;
            for (RunSummary summary : history) {
                sb.append("        <tr><td class=\"mono\">#").append(run++).append(" <span class=\"muted\">")
                        .append(escape(summary.timestamp())).append("</span></td>")
                        .append("<td class=\"num\">").append(summary.total()).append("</td>")
                        .append("<td class=\"num\">").append(summary.errors()).append("</td>")
                        .append("<td class=\"num\">").append(summary.warnings()).append("</td>")
                        .append("<td class=\"num\">").append(summary.infos()).append("</td>")
                        .append("<td class=\"num\">")
                        .append(String.format(Locale.ROOT, "%.1f%%", summary.duplicationPercent())).append("</td>")
                        .append("<td class=\"num\">")
                        .append(String.format(Locale.ROOT, "%.1f s", summary.durationMillis() / 1000.0))
                        .append("</td></tr>\n");
            }
            sb.append("      </tbody>\n    </table></div>\n  </div>\n");
        }

        sb.append("</section>\n");
    }

    private static Map<String, List<Violation>> byRule(List<Violation> violations) {
        Map<String, List<Violation>> grouped = new LinkedHashMap<>();
        for (Violation violation : violations) {
            grouped.computeIfAbsent(violation.ruleId(), key -> new ArrayList<>()).add(violation);
        }
        return grouped.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private static int severityRank(Severity severity) {
        return switch (severity) {
            case ERROR -> 0;
            case WARNING -> 1;
            case INFO -> 2;
        };
    }

    /** "1 block" / "2 blocks" — a report is read by people. */
    private static String plural(long count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    private static String percent(long value, long total) {
        return String.format(Locale.ROOT, "%.3f", total == 0 ? 0.0 : (value * 100.0) / total);
    }

    // ----------------------------------------------------------------- health

    private void healthPanel(StringBuilder sb, AnalysisReport report, List<Violation> accepted) {
        sb.append("<section id=\"tab-health\" class=\"tab-panel\" role=\"tabpanel\" ")
                .append("aria-labelledby=\"tabbtn-health\" tabindex=\"0\">\n");

        sb.append("  <div class=\"panel\">\n    <div class=\"panel__head\">\n")
                .append("      <h2>Run Diagnostics (").append(report.diagnostics().size()).append(")</h2>\n")
                .append("      <p class=\"panel__hint\">Non-fatal problems: a reported file was skipped or only ")
                .append("partially analysed.</p>\n    </div>\n");
        if (report.diagnostics().isEmpty()) {
            sb.append("    <div class=\"empty\"><span class=\"empty__mark\">✓</span>")
                    .append("<div>No parser or scan diagnostics: every discovered file was analysed.</div></div>\n");
        } else {
            sb.append("    <div class=\"table-scroll\"><table class=\"compact\">\n")
                    .append("      <thead><tr><th scope=\"col\">Kind</th><th scope=\"col\">Location</th>")
                    .append("<th scope=\"col\">Detail</th></tr></thead>\n      <tbody>\n");
            for (var diagnostic : report.diagnostics()) {
                String location = diagnostic.line() > 0
                        ? diagnostic.path() + ":" + diagnostic.line()
                        : diagnostic.path();
                sb.append("        <tr><td><span class=\"pill pill--warning\">")
                        .append(escape(diagnostic.kind().name())).append("</span></td>")
                        .append("<td><span class=\"loc\">").append(escape(location)).append("</span></td>")
                        .append("<td>").append(escape(diagnostic.reason())).append("</td></tr>\n");
            }
            sb.append("      </tbody>\n    </table></div>\n");
        }
        sb.append("  </div>\n");

        sb.append("  <div class=\"panel\">\n    <div class=\"panel__head\">\n")
                .append("      <h2>Accepted by Baseline <span class=\"count\">").append(accepted.size())
                .append("</span></h2>\n")
                .append("      <p class=\"panel__hint\">Frozen in <span class=\"mono\">.aem-analyser-baseline.json")
                .append("</span> and excluded from totals and exit codes.</p>\n    </div>\n");
        if (accepted.isEmpty()) {
            sb.append("    <div class=\"empty\"><span class=\"empty__mark\">·</span>")
                    .append("<div>No findings are suppressed by a baseline.</div></div>\n");
        } else {
            sb.append("    <div class=\"table-scroll\"><table class=\"compact\">\n")
                    .append("      <thead><tr><th scope=\"col\">Rule</th><th scope=\"col\">Location</th>")
                    .append("<th scope=\"col\">Message</th></tr></thead>\n      <tbody>\n");
            for (Violation violation : accepted) {
                sb.append("        <tr><td><a class=\"rule-code\" href=\"").append(escape(helpUriFor(violation.ruleId())))
                        .append("\" target=\"_blank\" rel=\"noopener\">").append(escape(violation.ruleId()))
                        .append("</a></td>")
                        .append("<td><span class=\"loc\">").append(escape(violation.filePath())).append(":")
                        .append(violation.region().startLine()).append("</span></td>")
                        .append("<td>").append(escape(violation.message())).append("</td></tr>\n");
            }
            sb.append("      </tbody>\n    </table></div>\n");
        }
        sb.append("  </div>\n</section>\n");
    }

    // ------------------------------------------------------------- duplication

    private void duplicationPanel(StringBuilder sb, AnalysisReport report, CodeMetrics metrics) {
        sb.append("<section id=\"tab-duplication\" class=\"tab-panel\" role=\"tabpanel\" ")
                .append("aria-labelledby=\"tabbtn-duplication\" tabindex=\"0\">\n");

        sb.append("  <div class=\"panel\">\n    <div class=\"panel__head\">\n")
                .append("      <h2>Classes with duplicated code <span class=\"count\">")
                .append(metrics.duplicateClasses().size()).append("</span></h2>\n")
                .append("      <p class=\"panel__hint\">From the embedded PMD CPD scan.</p>\n    </div>\n");
        sb.append("    <div class=\"table-scroll\"><table class=\"compact\">\n")
                .append("      <thead><tr><th scope=\"col\">Class</th><th scope=\"col\">File</th>")
                .append("<th scope=\"col\">Duplicated lines</th><th scope=\"col\">Share of class</th></tr></thead>\n")
                .append("      <tbody>\n");
        if (metrics.duplicateClasses().isEmpty()) {
            sb.append("        <tr><td colspan=\"4\" class=\"muted\">No duplicated code clones detected.</td></tr>\n");
        } else {
            for (var dupClass : metrics.duplicateClasses()) {
                sb.append("        <tr><td class=\"mono\">").append(escape(dupClass.className())).append("</td>")
                        .append("<td><span class=\"loc\">").append(escape(dupClass.filePath())).append("</span></td>")
                        .append("<td class=\"num\">").append(dupClass.duplicatedLines()).append("</td>")
                        .append("<td class=\"num\">")
                        .append(String.format(Locale.ROOT, "%.1f%%", dupClass.duplicationPercent()))
                        .append("</td></tr>\n");
            }
        }
        sb.append("      </tbody>\n    </table></div>\n  </div>\n");

        if (!metrics.duplicateBlockDetails().isEmpty()) {
            sb.append("  <div class=\"panel\">\n    <div class=\"panel__head\">\n")
                    .append("      <h2>Duplicate Blocks (").append(metrics.duplicateBlockDetails().size())
                    .append(")</h2>\n")
                    .append("      <p class=\"panel__hint\">Duplicate and original location for each clone.</p>\n")
                    .append("    </div>\n");
            sb.append("    <div class=\"table-scroll\"><table class=\"compact\">\n")
                    .append("      <thead><tr><th scope=\"col\">Kind</th><th scope=\"col\">Duplicate</th>")
                    .append("<th scope=\"col\">Original</th><th scope=\"col\">Lines</th>")
                    .append("<th scope=\"col\">Code lines</th><th scope=\"col\">Tokens</th>")
                    .append("<th scope=\"col\">Fingerprint</th><th scope=\"col\">Preview</th></tr></thead>\n")
                    .append("      <tbody>\n");
            for (CodeMetrics.DuplicateBlock block : metrics.duplicateBlockDetails()) {
                sb.append("        <tr><td><span class=\"pill pill--info\">")
                        .append(escape(classifyDuplicate(report.projectRoot(), block))).append("</span></td>")
                        .append("<td><span class=\"loc\">").append(escape(block.duplicateFile())).append(":")
                        .append(block.duplicateStartLine()).append("-").append(block.duplicateEndLine())
                        .append("</span></td>")
                        .append("<td><span class=\"loc\">").append(escape(block.originalFile())).append(":")
                        .append(block.originalStartLine()).append("-").append(block.originalEndLine())
                        .append("</span></td>")
                        .append("<td class=\"num\">").append(block.duplicatedLines()).append("</td>")
                        .append("<td class=\"num\">").append(block.codeLines()).append("</td>")
                        .append("<td class=\"num\">").append(block.tokens()).append("</td>")
                        .append("<td><span class=\"mono small\">").append(escape(block.fingerprint()))
                        .append("</span></td>")
                        .append("<td><details><summary>Show duplicated lines</summary><pre class=\"snippet\">")
                        .append(escape(previewLines(report.projectRoot(), block)))
                        .append("</pre></details></td></tr>\n");
            }
            sb.append("      </tbody>\n    </table></div>\n  </div>\n");
        }
        sb.append("</section>\n");
    }

    // ---------------------------------------------------------------- coverage

    private void coveragePanel(StringBuilder sb, CoverageReport coverage) {
        sb.append("<section id=\"tab-coverage\" class=\"tab-panel\" role=\"tabpanel\" ")
                .append("aria-labelledby=\"tabbtn-coverage\" tabindex=\"0\">\n");
        sb.append("  <div class=\"panel\">\n    <div class=\"panel__head\"><h2>JaCoCo coverage</h2></div>\n");

        if (coverage != null && coverage.present()) {
            sb.append("    <div class=\"grid\">\n      <div class=\"stat\">\n        <h3>Lines</h3>\n")
                    .append("        <div class=\"stat__big\">")
                    .append(String.format(Locale.ROOT, "%.1f%%", coverage.lineCoveragePercent()))
                    .append("</div>\n        <p class=\"muted\">")
                    .append(coverage.coveredLines()).append(" of ").append(coverage.totalLines())
                    .append(" lines covered</p>\n      </div>\n")
                    .append("      <div class=\"stat\">\n        <h3>Branches</h3>\n")
                    .append("        <div class=\"stat__big\">")
                    .append(String.format(Locale.ROOT, "%.1f%%", coverage.branchCoveragePercent()))
                    .append("</div>\n        <p class=\"muted\">")
                    .append(coverage.coveredBranches()).append(" of ").append(coverage.totalBranches())
                    .append(" branches covered</p>\n      </div>\n")
                    .append("      <div class=\"stat\">\n        <h3>Source</h3>\n")
                    .append("        <p class=\"mono small\">")
                    .append(escape(coverage.sourcePath() == null ? "jacoco.xml" : coverage.sourcePath()))
                    .append("</p>\n      </div>\n    </div>\n");
        } else {
            sb.append("    <div class=\"empty\"><span class=\"empty__mark\">·</span><div>")
                    .append("No JaCoCo coverage file was detected in the standard target directories. ")
                    .append("Run with <span class=\"mono\">--collect-coverage</span> to execute unit tests ")
                    .append("and generate coverage data.</div></div>\n");
        }
        sb.append("  </div>\n</section>\n");
    }

    // ---------------------------------------------------------------- auto-fix

    private void autoFixPanel(StringBuilder sb) {
        sb.append("<section id=\"tab-autofix\" class=\"tab-panel\" role=\"tabpanel\" ")
                .append("aria-labelledby=\"tabbtn-autofix\" tabindex=\"0\">\n");
        sb.append("  <div class=\"panel\">\n    <div class=\"panel__head\">\n")
                .append("      <h2>Safe automated fixes</h2>\n")
                .append("      <p class=\"panel__hint\">Deterministic repairs that <span class=\"mono\">--fix")
                .append("</span> applies in place.</p>\n    </div>\n");
        sb.append("    <div class=\"table-scroll\"><table class=\"compact\">\n")
                .append("      <thead><tr><th scope=\"col\">SonarJava rule</th>")
                .append("<th scope=\"col\">What it rewrites</th></tr></thead>\n      <tbody>\n")
                .append("        <tr><td class=\"mono\">java:S1128</td><td>Removes redundant duplicate import ")
                .append("statements, keeping package and class layout intact.</td></tr>\n")
                .append("        <tr><td class=\"mono\">java:S1125</td><td>Drops redundant ")
                .append("<span class=\"mono\">== true</span> and <span class=\"mono\">== false</span> ")
                .append("comparisons.</td></tr>\n")
                .append("        <tr><td class=\"mono\">java:S4719</td><td>Replaces the literal ")
                .append("<span class=\"mono\">&quot;UTF-8&quot;</span> in <span class=\"mono\">.getBytes()</span> ")
                .append("with <span class=\"mono\">StandardCharsets.UTF_8</span> and adds the import.</td></tr>\n")
                .append("      </tbody>\n    </table></div>\n");
        sb.append("    <div class=\"callout\">\n")
                .append("      <p><strong>Preview first.</strong> <span class=\"mono\">--fix-dry-run</span> ")
                .append("prints the diff without touching files; <span class=\"mono\">--fix</span> writes it.</p>\n")
                .append("    </div>\n  </div>\n</section>\n");
    }

    // ----------------------------------------------------------------- helpers

    /**
     * The report timestamp. A reproducible run renders the fixed instant in UTC, because a
     * timestamp formatted in the machine's local zone would make the bytes depend on where the
     * build ran; a live run keeps the local zone, which is what a developer expects to read.
     */
    private String generatedAt() {
        if (omitDuration) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
                    .withZone(java.time.ZoneOffset.UTC)
                    .format(Instant.now(clock));
        }
        return ISO_FORMATTER.format(Instant.now(clock));
    }

    /**
     * How the analysed root is shown in the header: relative to the working directory when it is
     * inside it (CI logs and committed sample reports should not carry absolute agent paths), else
     * relative to the user's home, else as given.
     */
    private static String displayRoot(Path root) {
        if (root == null) {
            return "";
        }
        Path absolute = root.toAbsolutePath().normalize();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (absolute.startsWith(cwd)) {
            Path relative = cwd.relativize(absolute);
            return relative.toString().isEmpty() ? "." : relative.toString();
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && absolute.startsWith(home)) {
            return "~" + absolute.toString().substring(home.length());
        }
        return absolute.toString();
    }

    /**
     * Escapes a path and adds break opportunities after separators, so a long package or config path
     * wraps at a slash or a dot instead of being cut mid-token. The raw value is untouched in
     * {@code data-path}, which is what exports and copy actions read.
     */
    private static String breakablePath(String path) {
        return escape(path).replace("/", "/<wbr>").replace(".", ".<wbr>");
    }

    /**
     * The documentation target for a rule id. AEM rules live on the published issue reference; the
     * embedded SonarJava analyser's rules have no page there, so they link to SonarSource instead of
     * producing an anchor that does not exist.
     */
    private static String helpUriFor(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            return RULE_DOCS;
        }
        if (ruleId.startsWith("java:")) {
            return "https://rules.sonarsource.com/java/rule/" + ruleId.substring("java:".length()) + "/";
        }
        return RULE_DOCS + ruleId.toLowerCase(Locale.ROOT);
    }

    private long countBySeverity(List<Violation> violations, Severity severity) {
        return violations.stream().filter(violation -> violation.severity() == severity).count();
    }

    /**
     * One sentence for the meta description and the link previews: what was scanned and what it
     * found. Empty severity buckets are left out, so a warnings-only report never reads "0 errors".
     */
    static String metaDescription(AnalysisReport report, long total, long errors, long warnings, long infos) {
        StringBuilder text = new StringBuilder("AEM Code Analyser report — ");
        if (total == 0) {
            text.append("no findings");
        } else {
            List<String> parts = new ArrayList<>();
            if (errors > 0) {
                parts.add(errors + (errors == 1 ? " error" : " errors"));
            }
            if (warnings > 0) {
                parts.add(warnings + (warnings == 1 ? " warning" : " warnings"));
            }
            if (infos > 0) {
                parts.add(infos + (infos == 1 ? " info" : " infos"));
            }
            text.append(total).append(total == 1 ? " finding" : " findings")
                    .append(" (").append(String.join(", ", parts)).append(')');
        }
        String project = projectName(report);
        if (!project.isEmpty()) {
            text.append(" for ").append(project);
        }
        return text.append('.').toString();
    }

    /** The last path element, not the absolute path: a report must not leak the machine it ran on. */
    private static String projectName(AnalysisReport report) {
        Path root = report.projectRoot();
        if (root == null || root.getFileName() == null) {
            return "";
        }
        return root.getFileName().toString();
    }

    /** Maps a rule id to the family used by the dashboard's filter chips. */
    private String getRuleCategory(String ruleId) {
        if (ruleId == null) {
            return "OTHER";
        }
        if (ruleId.startsWith("java:S")) {
            return "SONAR";
        }
        if (ruleId.startsWith("AEM-PERF-")) {
            return "PERF";
        }
        if (ruleId.startsWith("AEM-CLOUD-")) {
            return "CLOUD";
        }
        if (ruleId.startsWith("AEM-SEC-")) {
            return "SEC";
        }
        if (ruleId.startsWith("AEM-DEAD-")) {
            return "DEAD";
        }
        if (ruleId.startsWith("AEM-DUP-")) {
            return "DUP";
        }
        if (ruleId.startsWith("AEM-QUAL-")) {
            return "QUAL";
        }
        if (ruleId.startsWith("AEM-COMP-")) {
            return "COMP";
        }
        return "OTHER";
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

    private static String escape(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;")
                .replace("\r", "&#13;")
                .replace("\n", "&#10;")
                .replaceAll("[\\p{Cntrl}&&[^\\x09]]", "");
    }

    // ------------------------------------------------------------------ assets

    private static final String STYLES = """
            :root {
              color-scheme: light;
              --paper: #fbfbf9; --surface: #ffffff; --surface-2: #f5f6f3;
              --ink: #0e1417; --ink-2: #33424b; --muted: #5f7079;
              --line: #e3e5e1; --line-strong: #cbd0cb;
              --accent: #0a6a63; --accent-soft: #e6f1ef;
              --err: #b42318; --err-soft: #fdecea;
              --warn: #b54708; --warn-soft: #fdf3e5;
              --info: #175cd3; --info-soft: #eaf1fd;
              --ok: #067647; --ok-soft: #e7f6ec;
              --code-bg: #0f1719; --code-ink: #dfe7e6;
              --mono: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
              --sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
              --radius: 10px; --radius-sm: 6px;
              --shadow: 0 1px 2px rgba(14,20,23,.05), 0 10px 26px -20px rgba(14,20,23,.5);
            }
            [data-theme="dark"] {
              color-scheme: dark;
              --paper: #0b1013; --surface: #10181b; --surface-2: #0e1619;
              --ink: #e9efee; --ink-2: #c2cfcd; --muted: #8b9a99;
              --line: #1f2c2f; --line-strong: #2c3d40;
              --accent: #57c9bd; --accent-soft: #122a29;
              --err: #ff9d92; --err-soft: #2a1512;
              --warn: #f2b268; --warn-soft: #2a1e10;
              --info: #93b9ff; --info-soft: #101f38;
              --ok: #6fd39a; --ok-soft: #0e2418;
              --code-bg: #0a1013; --code-ink: #d7e2e0;
            }
            * { box-sizing: border-box; }
            html { -webkit-text-size-adjust: 100%; }
            body { margin: 0; background: var(--paper); color: var(--ink);
                   font: 15px/1.55 var(--sans); -webkit-font-smoothing: antialiased; }
            h2 { font-size: .98rem; font-weight: 600; margin: 0; letter-spacing: -.01em; }
            h3 { font-size: .72rem; font-family: var(--mono); text-transform: uppercase;
                 letter-spacing: .08em; color: var(--muted); font-weight: 500; margin: 0; }
            p { margin: 0 0 .6rem; }
            a { color: var(--accent); text-underline-offset: 2px; }
            a:hover { text-decoration-thickness: 2px; }
            :focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; border-radius: 3px; }
            .mono, code, .loc, .rule-code, .kpi__label, .kpi__note { font-family: var(--mono); }
            .muted { color: var(--muted); }
            .small { font-size: .8rem; }
            .visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
                               overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0; }
            .skip { position: absolute; left: -9999px; top: 0; background: var(--ink); color: var(--paper);
                    padding: .5rem .8rem; z-index: 99; }
            .skip:focus { left: 0; }
            .noscript { margin: 0; padding: .7rem 1.2rem; background: var(--warn-soft); color: var(--warn);
                        border-bottom: 1px solid var(--line); font-size: .85rem; }

            .top { border-bottom: 1px solid var(--line); background: var(--surface); }
            .top__inner { display: flex; align-items: center; gap: 1rem; flex-wrap: wrap;
                          padding: .85rem clamp(1rem, 3vw, 2rem) .5rem; max-width: 1440px; margin: 0 auto; }
            .brand { display: flex; align-items: center; gap: .55rem; font-weight: 600; letter-spacing: -.01em; }
            .brand__mark { display: grid; place-items: center; width: 26px; height: 26px; border-radius: 5px;
                           background: var(--ink); color: var(--paper); font-family: var(--mono);
                           font-size: .6rem; letter-spacing: -.03em; }
            [data-theme="dark"] .brand__mark { background: var(--accent); color: #06201f; }
            .brand__title { font-size: 1rem; font-weight: 600; margin: 0; letter-spacing: -.01em; }
            .brand__version { font-size: .7rem; color: var(--muted); border: 1px solid var(--line);
                              border-radius: 999px; padding: .02rem .4rem; }
            .top__actions { margin-left: auto; display: flex; align-items: center; gap: .5rem; }
            .verdict { font-family: var(--mono); font-size: .68rem; font-weight: 500; letter-spacing: .04em;
                       padding: .3rem .55rem; border-radius: 5px; border: 1px solid transparent;
                       white-space: nowrap; }
            .verdict--fail { background: var(--err-soft); color: var(--err); border-color: var(--err); }
            .verdict--warn { background: var(--warn-soft); color: var(--warn); border-color: var(--warn); }
            .verdict--pass { background: var(--ok-soft); color: var(--ok); border-color: var(--ok); }
            .icon { display: grid; place-items: center; width: 30px; height: 30px; border-radius: var(--radius-sm);
                    border: 1px solid var(--line); background: var(--surface); color: var(--ink-2);
                    cursor: pointer; }
            .icon:hover { border-color: var(--line-strong); color: var(--ink); }
            .top__meta { display: flex; flex-wrap: wrap; gap: .3rem 1.1rem;
                         padding: 0 clamp(1rem, 3vw, 2rem) .7rem; max-width: 1440px; margin: 0 auto;
                         font-size: .74rem; color: var(--muted); }
            .top__meta .mono { font-size: .72rem; color: var(--ink-2); }

            .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(128px, 1fr)); gap: 1px;
                    background: var(--line); border-bottom: 1px solid var(--line); }
            .kpi { background: var(--surface); padding: .8rem clamp(.7rem, 2vw, 1.2rem); }
            .kpi__label { font-size: .64rem; letter-spacing: .08em; text-transform: uppercase;
                          color: var(--muted); }
            .kpi__value { font-size: 1.45rem; font-weight: 600; letter-spacing: -.03em; margin-top: .1rem;
                          font-variant-numeric: tabular-nums; }
            .kpi__note { font-size: .68rem; color: var(--muted); }
            .kpi--error .kpi__value { color: var(--err); }
            .kpi--warn .kpi__value { color: var(--warn); }
            .kpi--info .kpi__value { color: var(--info); }

            .tabbar { display: flex; gap: .1rem; overflow-x: auto; padding: .4rem clamp(1rem, 3vw, 2rem) 0;
                      border-bottom: 1px solid var(--line); background: var(--surface-2);
                      scrollbar-width: thin; }
            .tab { font: inherit; font-size: .82rem; font-weight: 500; color: var(--muted);
                   background: transparent; border: 0; border-bottom: 2px solid transparent;
                   padding: .55rem .7rem; cursor: pointer; white-space: nowrap; }
            .tab:hover { color: var(--ink); }
            .tab.is-active { color: var(--ink); border-bottom-color: var(--accent); }
            .tab-panel { display: none; padding: clamp(1rem, 2.5vw, 1.5rem) clamp(1rem, 3vw, 2rem) 2rem;
                         max-width: 1440px; margin: 0 auto; }
            .tab-panel.is-active { display: block; }

            .panel { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius);
                     box-shadow: var(--shadow); margin-bottom: 1.1rem; overflow: hidden; }
            .panel__head { display: flex; flex-wrap: wrap; align-items: baseline; gap: .2rem .8rem;
                           padding: .8rem 1rem; border-bottom: 1px solid var(--line); }
            .panel__hint { margin: 0; font-size: .76rem; color: var(--muted); }
            .count { font-family: var(--mono); font-size: .72rem; color: var(--muted);
                     border: 1px solid var(--line); border-radius: 999px; padding: 0 .4rem; margin-left: .2rem; }

            .toolbar { display: flex; flex-wrap: wrap; gap: .5rem .6rem; align-items: center;
                       padding: .7rem 1rem; border-bottom: 1px solid var(--line); background: var(--surface-2);
                       position: sticky; top: 0; z-index: 5; }
            .search { flex: 1 1 240px; min-height: 32px; padding: .35rem .6rem; font: inherit; font-size: .82rem;
                      color: var(--ink); background: var(--surface); border: 1px solid var(--line-strong);
                      border-radius: var(--radius-sm); }
            .chips { display: flex; gap: .25rem; flex-wrap: wrap; }
            .chip { font: inherit; font-family: var(--mono); font-size: .7rem; color: var(--ink-2);
                    background: var(--surface); border: 1px solid var(--line-strong); border-radius: 999px;
                    padding: .2rem .5rem; cursor: pointer; }
            .chip span { color: var(--muted); }
            .chip:hover { border-color: var(--ink-2); color: var(--ink); }
            .chip.is-active { background: var(--ink); border-color: var(--ink); color: var(--paper); }
            .chip.is-active span { color: inherit; opacity: .7; }
            .field { display: flex; align-items: center; gap: .3rem; font-size: .76rem; color: var(--muted); }
            .field select { font: inherit; font-size: .76rem; color: var(--ink); background: var(--surface);
                            border: 1px solid var(--line-strong); border-radius: var(--radius-sm);
                            padding: .22rem .3rem; }
            .tools { display: flex; gap: .3rem; flex-wrap: wrap; }
            .btn { font: inherit; font-family: var(--mono); font-size: .7rem; color: var(--ink-2);
                   background: var(--surface); border: 1px solid var(--line-strong);
                   border-radius: var(--radius-sm); padding: .28rem .5rem; cursor: pointer; }
            .btn:hover { border-color: var(--ink-2); color: var(--ink); }
            .result-count { margin-left: auto; font-family: var(--mono); font-size: .72rem; color: var(--muted); }

            .table-scroll { overflow-x: auto; }
            table { width: 100%; border-collapse: collapse; font-size: .84rem; }
            thead th { text-align: left; font-family: var(--mono); font-size: .64rem; letter-spacing: .07em;
                       text-transform: uppercase; color: var(--muted); font-weight: 500; padding: .55rem .8rem;
                       background: var(--surface-2); border-bottom: 1px solid var(--line); white-space: nowrap; }
            tbody td { padding: .6rem .8rem; border-bottom: 1px solid var(--line); vertical-align: top; }
            tbody tr:last-child td { border-bottom: 0; }
            tbody tr:hover { background: color-mix(in srgb, var(--surface-2) 65%, transparent); }
            .compact tbody td { padding: .45rem .8rem; }
            .num { text-align: right; font-variant-numeric: tabular-nums; font-family: var(--mono);
                   font-size: .78rem; }
            th.sev { width: 96px; }
            th.rule { width: 172px; }
            th.sortable { cursor: pointer; user-select: none; }
            th.sortable:hover { color: var(--accent); }
            th[aria-sort="ascending"] .sort-indicator::after { content: " \\2191"; }
            th[aria-sort="descending"] .sort-indicator::after { content: " \\2193"; }
            th[aria-sort="ascending"], th[aria-sort="descending"] { color: var(--ink); }

            .pill { display: inline-block; font-family: var(--mono); font-size: .63rem; font-weight: 500;
                    letter-spacing: .03em; text-transform: uppercase; padding: .1rem .36rem;
                    border-radius: 4px; border: 1px solid transparent; }
            .pill--error { color: var(--err); background: var(--err-soft); border-color: var(--err); }
            .pill--warning { color: var(--warn); background: var(--warn-soft); border-color: var(--warn); }
            .pill--info { color: var(--info); background: var(--info-soft); border-color: var(--info); }
            .rule-code { font-size: .76rem; color: var(--accent); text-decoration: none; }
            .rule-code:hover { text-decoration: underline; }
            .permalink { margin-left: .3rem; font-size: .72rem; color: var(--muted); text-decoration: none;
                         opacity: .6; }
            .permalink:hover { opacity: 1; color: var(--accent); }
            .loc { font-size: .76rem; color: var(--ink-2); word-break: break-word; }
            .finding { margin: 0 0 .3rem; font-weight: 500; }
            .fix { margin: 0 0 .3rem; font-size: .8rem; color: var(--ink-2); background: var(--surface-2);
                   border-radius: 4px; padding: .3rem .5rem; }
            .fix strong { font-family: var(--mono); font-size: .64rem; text-transform: uppercase;
                          letter-spacing: .06em; color: var(--muted); margin-right: .4rem; }
            .related { margin: 0 0 .2rem; font-size: .74rem; color: var(--muted); }
            .fix-link { font-size: .74rem; color: var(--accent); text-decoration: none; }
            .fix-link:hover { text-decoration: underline; }
            tr:target { outline: 2px solid var(--accent); outline-offset: -2px; }
            tr.group-row td { background: var(--surface-2); font-family: var(--mono); font-size: .68rem;
                              letter-spacing: .05em; text-transform: uppercase; color: var(--ink-2); }
            .group-count { margin-left: .4rem; padding: 0 .4rem; border-radius: 999px;
                           background: var(--line-strong); color: var(--paper); font-size: .62rem; }
            .empty-row td { padding: 0; }
            .empty { display: flex; gap: .7rem; align-items: flex-start; padding: 1.1rem 1rem;
                     font-size: .86rem; color: var(--ink-2); }
            .empty__mark { display: grid; place-items: center; width: 22px; height: 22px; border-radius: 50%;
                           background: var(--ok-soft); color: var(--ok); font-size: .8rem; flex: none; }
            .omitted-row td { text-align: center; font-size: .76rem; color: var(--muted); padding: 1rem; }

            .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(212px, 1fr)); gap: 1px;
                    background: var(--line); }
            .stat { background: var(--surface); padding: .9rem 1rem; }
            .stat__big { font-size: 1.7rem; font-weight: 600; letter-spacing: -.03em;
                         font-variant-numeric: tabular-nums; }
            .kv { list-style: none; margin: .4rem 0 0; padding: 0; }
            .kv li { display: flex; justify-content: space-between; gap: .8rem; padding: .2rem 0;
                     font-size: .8rem; border-bottom: 1px dashed var(--line); }
            .kv li:last-child { border-bottom: 0; }
            .kv span { color: var(--muted); }
            .kv strong { font-weight: 600; font-variant-numeric: tabular-nums; }
            .t-error { color: var(--err); } .t-warn { color: var(--warn); } .t-info { color: var(--info); }
            .bar { display: flex; height: 8px; border-radius: 999px; overflow: hidden; background: var(--line);
                   margin: .5rem 0 .2rem; }
            .bar--error { background: var(--err); } .bar--warn { background: var(--warn); }
            .bar--info { background: var(--info); }

            .snippet { margin: .3rem 0 0; padding: .5rem .6rem; background: var(--code-bg); color: var(--code-ink);
                       border-radius: 4px; font-family: var(--mono); font-size: .7rem; line-height: 1.5;
                       overflow-x: auto; white-space: pre; }
            details summary { cursor: pointer; font-size: .76rem; color: var(--accent); }
            .callout { margin: 0 1rem 1rem; padding: .6rem .8rem; border-radius: var(--radius-sm);
                       border: 1px solid var(--line); background: var(--surface-2); font-size: .82rem; }
            .callout p:last-child { margin-bottom: 0; }

            .foot { display: flex; flex-wrap: wrap; gap: .4rem 1.4rem; justify-content: space-between;
                    padding: 1rem clamp(1rem, 3vw, 2rem) 2rem; max-width: 1440px; margin: 0 auto;
                    font-size: .74rem; color: var(--muted); }
            .foot .mono { font-size: .7rem; }
            #toast { position: fixed; right: 1rem; bottom: 1rem; background: var(--ink); color: var(--paper);
                     padding: .55rem .8rem; border-radius: var(--radius-sm); font-size: .78rem;
                     font-weight: 500; opacity: 0; transform: translateY(.4rem);
                     transition: opacity .18s ease, transform .18s ease; pointer-events: none; z-index: 60; }
            #toast.show { opacity: 1; transform: none; }

            @media (max-width: 760px) {
              /* The tab strip scrolls: give it a fade and trailing room so the cut is obviously a scroll. */
              .tabbar {
                scroll-snap-type: x proximity;
                padding-right: 2.5rem;
                mask-image: linear-gradient(to right, #000 92%, transparent 100%);
                -webkit-mask-image: linear-gradient(to right, #000 92%, transparent 100%);
              }
              .tab { scroll-snap-align: start; }
              .toolbar { position: static; }
              .result-count { margin-left: 0; width: 100%; }
              table, thead, tbody, tr, td { display: block; }
              thead { display: none; }
              tbody tr.violation-row { border: 1px solid var(--line); border-radius: var(--radius-sm);
                                       margin: .6rem; padding: .5rem .6rem; background: var(--surface); }
              tbody tr.violation-row td { border: 0; padding: .22rem 0; }
              tbody td[data-label]::before { content: attr(data-label); display: block; font-family: var(--mono);
                                             font-size: .6rem; letter-spacing: .07em;
                                             text-transform: uppercase; color: var(--muted); }
              tbody tr.group-row { border: 0; margin: .5rem .6rem 0; }
              tbody tr.group-row td { border-radius: 4px; padding: .3rem .5rem; }
              .omitted-row td, .empty-row td { text-align: left; }
              .grid { grid-template-columns: 1fr; }
            }

            @media (prefers-reduced-motion: reduce) {
              * { transition-duration: .001ms !important; animation-duration: .001ms !important; }
            }
            @media print {
              body { background: #fff; color: #000; }
              .top, .kpis, .panel, .foot { box-shadow: none; }
              .tabbar, .toolbar, .top__actions, .permalink, .fix-link, #toast, .skip {
                display: none !important;
              }
              .tab-panel { display: block !important; page-break-inside: avoid; }
              .panel { page-break-inside: avoid; border-color: #999; }
              a { color: #000; text-decoration: none; }
              .snippet, .fix { background: #fff; color: #000; border: 1px solid #ccc; }
            }
            """;

    private static final String SCRIPT = """
            (function () {
              'use strict';

              var root = document.documentElement;
              var themeToggle = document.getElementById('themeToggle');
              var toast = document.getElementById('toast');
              var searchInput = document.getElementById('searchInput');
              var filterButtons = Array.prototype.slice.call(document.querySelectorAll('.chip'));
              var tabButtons = Array.prototype.slice.call(document.querySelectorAll('.tab'));
              var tabPanels = Array.prototype.slice.call(document.querySelectorAll('.tab-panel'));
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
                window.setTimeout(function () { toast.classList.remove('show'); }, 1700);
              }

              function store(key, value) {
                try { localStorage.setItem(key, value); } catch (e) { }
              }
              function read(key) {
                try { return localStorage.getItem(key); } catch (e) { return null; }
              }

              function applyTheme(value) {
                root.setAttribute('data-theme', value);
                if (themeToggle) {
                  themeToggle.setAttribute('aria-pressed', value === 'dark' ? 'true' : 'false');
                  themeToggle.setAttribute('aria-label',
                    value === 'dark' ? 'Switch to light mode' : 'Switch to dark mode');
                }
              }
              var initialTheme = read('aem-analyser-theme');
              if (!initialTheme) {
                initialTheme = window.matchMedia
                  && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
              }
              applyTheme(initialTheme);
              if (themeToggle) {
                themeToggle.addEventListener('click', function () {
                  var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
                  applyTheme(next);
                  store('aem-analyser-theme', next);
                });
              }

              function activateTab(id, focus) {
                var found = false;
                tabButtons.forEach(function (button) {
                  var selected = button.getAttribute('data-tab') === id;
                  button.classList.toggle('is-active', selected);
                  button.setAttribute('aria-selected', selected ? 'true' : 'false');
                  button.tabIndex = selected ? 0 : -1;
                  if (selected && focus) button.focus();
                  found = found || selected;
                });
                if (!found) {
                  if (!'tab-violations'.equals(id)) {
                    activateTab('tab-violations', focus);
                    return;
                  }
                  id = 'tab-violations';
                }
                tabPanels.forEach(function (panel) {
                  panel.classList.toggle('is-active', panel.id === id);
                });
                activeTab = id;
              }

              tabButtons.forEach(function (button, index) {
                button.addEventListener('click', function () {
                  activateTab(button.getAttribute('data-tab'), false);
                  syncHash();
                });
                button.addEventListener('keydown', function (event) {
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

              function rows() {
                return tbody ? Array.prototype.slice.call(tbody.querySelectorAll('tr.violation-row')) : [];
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
                resultCount.textContent = visible + ' of ' + list.length + (list.length === 1 ? ' finding shown' : ' findings shown');
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
                Array.prototype.slice.call(tbody.querySelectorAll('tr.group-row')).forEach(function (row) {
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
                  var left, right;
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

              filterButtons.forEach(function (button) {
                button.addEventListener('click', function () {
                  if (!button.getAttribute('data-filter') && !button.getAttribute('data-category')) return;
                  filterButtons.forEach(function (other) { other.classList.remove('is-active'); });
                  button.classList.add('is-active');
                  if (button.getAttribute('data-filter')) {
                    activeFilter = button.getAttribute('data-filter');
                    activeCategory = null;
                  } else {
                    activeFilter = 'all';
                    activeCategory = button.getAttribute('data-category');
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
                  filterButtons.forEach(function (button) {
                    button.classList.toggle('is-active', button.getAttribute('data-filter') === 'all');
                  });
                  sortRows('severity', 'asc');
                  applyFilters();
                });
              }

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
                var text = String(value == null ? '' : value);
                if (/^[=+\\-@\\t\\r]/.test(text)) text = "'" + text;
                return '"' + text.replace(/"/g, '""') + '"';
              }

              function toCsv(data) {
                var lines = ['Severity,Rule,File,Line,Message,Remediation'];
                data.forEach(function (item) {
                  lines.push([item.severity, item.rule, item.file, item.line, item.message,
                    item.remediation].map(csvCell).join(','));
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
                  out.push('| ' + item.severity + ' | ' + item.rule + ' | ' + item.file + ':' + item.line
                    + ' | ' + issue + ' |');
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
                  }, function () { fallbackCopy(text); });
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
                copyLink.addEventListener('click', function () { copyText(window.location.href); });
              }

              function syncHash() {
                if (!window.history || !window.history.replaceState) return;
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
                window.history.replaceState(null, '',
                  next ? '#' + next : window.location.pathname + window.location.search);
              }

              function restoreFromHash() {
                var hash = window.location.hash || '';
                if (/^#f-[0-9]+$/.test(hash)) {
                  activateTab('tab-violations', false);
                  return;
                }
                var params = new URLSearchParams(hash.replace(/^#/, ''));
                if (params.get('tab')) activateTab(params.get('tab'), false);
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
                filterButtons.forEach(function (button) {
                  var isAll = button.getAttribute('data-filter') === activeFilter && !activeCategory;
                  var isCategory = activeCategory
                    && button.getAttribute('data-category') === activeCategory;
                  button.classList.toggle('is-active', Boolean(isAll || isCategory));
                });
                sortRows(sortKey, sortDir);
                applyFilters();
              }

              updateSortIndicators();
              restoreFromHash();
              updateCount();
            })();
            """;
}
