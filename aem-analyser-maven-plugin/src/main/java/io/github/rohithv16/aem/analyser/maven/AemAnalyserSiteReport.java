package io.github.rohithv16.aem.analyser.maven;

import io.github.rohithv16.aem.analyser.core.api.AemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.export.HtmlReportExporter;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.reporting.MavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.codehaus.doxia.sink.Sink;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Issue #45: findings in {@code mvn site}, beside CPD and JaCoCo.
 *
 * <p>{@code mvn site} is where a reviewer already reads the build's reports; before this goal the
 * analyser contributed its own CPD and coverage output while omitting itself. The page this report
 * renders is the sink-rendered summary — verdict, severity counts, one row per rule that fired with
 * a link to its documentation — and it links to the full interactive dashboard, which carries every
 * finding.
 *
 * <p>A site report is a <em>documentation</em> goal, not a gate: it never throws
 * {@link MavenReportException} or {@link org.apache.maven.plugin.MojoFailureException} because a
 * finding must not break {@code mvn site} (the gate is {@code aem-analyser:check}). A failed
 * analysis is rendered as a warning in the page and logged, so the site still builds.
 *
 * <p>The goal is <em>opt-in</em>: it only runs when the plugin is listed under
 * {@code <reporting><plugins>}, which is Maven's own contract for site reports.
 */
@Mojo(name = "aem-analyser",
        aggregator = true,
        threadSafe = true,
        requiresDependencyResolution = ResolutionScope.NONE)
public class AemAnalyserSiteReport extends AbstractAnalyserMojo implements MavenReport {

    /** The site page name; Maven derives {@code aem-analyser.html} from it. */
    static final String OUTPUT_NAME = "aem-analyser";

    /** The full dashboard the summary page links to, in the same directory. */
    static final String DASHBOARD_NAME = "aem-analyser-dashboard.html";

    /** Published rule documentation, anchors are the lower-cased rule id. */
    private static final String RULE_DOCS = "https://rohithv16.github.io/aem-code-analyser/issues/#";

    private File reportOutputDirectory;

    /** Cached so one site build analyses the reactor once, however often Maven asks for the page. */
    private AnalysisReport report;

    /** Non-null when the analysis could not run; rendered as a warning instead of failing the site. */
    private String analysisFailure;

    @Override
    protected String goalName() {
        return "aem-analyser";
    }

    @Override
    protected String defaultFormats() {
        return "HTML";
    }

    /**
     * Direct invocation ({@code mvn aem-analyser:aem-analyser}): writes the dashboard and logs the
     * summary. Site builds go through {@link #generate(Sink, Locale)} instead.
     */
    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("aem-analyser:aem-analyser skipped (aem-analyser.skip=true)");
            return;
        }
        AnalysisReport analysed = analysis();
        writeDashboard(analysed);
        logSummary(analysed);
    }

    @Override
    public void generate(Sink sink, Locale locale) throws MavenReportException {
        AnalysisReport analysed = analysis();
        writeDashboard(analysed);
        render(sink, analysed);
    }

    @Override
    public String getOutputName() {
        return OUTPUT_NAME;
    }

    @Override
    public String getName(Locale locale) {
        return "AEM Code Analyser";
    }

    @Override
    public String getDescription(Locale locale) {
        return "Cross-boundary dead code, duplication and AEM as a Cloud Service findings.";
    }

    @Override
    public String getCategoryName() {
        return MavenReport.CATEGORY_PROJECT_REPORTS;
    }

    @Override
    public void setReportOutputDirectory(File reportOutputDirectory) {
        this.reportOutputDirectory = reportOutputDirectory;
    }

    @Override
    public File getReportOutputDirectory() {
        if (reportOutputDirectory == null) {
            reportOutputDirectory = project != null && project.getReporting() != null
                    ? new File(project.getReporting().getOutputDirectory())
                    : new File("target/site");
        }
        return reportOutputDirectory;
    }

    @Override
    public boolean isExternalReport() {
        return false;
    }

    @Override
    public boolean canGenerateReport() {
        return !skip;
    }

    /**
     * Runs the analysis once per build. Never throws: a report that cannot be generated must not
     * break {@code mvn site}, so the failure is recorded and rendered.
     */
    private AnalysisReport analysis() {
        if (report != null) {
            return report;
        }
        try {
            Path root = resolveRoot();
            getLog().info("AEM Code Analyser: analysing " + root + " for the site report");
            AemCodeAnalyser analyser = new DefaultAemCodeAnalyser();
            report = analyser.analyze(buildContext(root));
        } catch (Exception e) {
            analysisFailure = e.getMessage() == null ? e.toString() : e.getMessage();
            getLog().warn("AEM Code Analyser: the site report could not analyse the project: "
                    + analysisFailure);
            report = new AnalysisReport(resolveRoot(), Map.of(), List.of(), 0L);
        }
        return report;
    }

    /** Writes the interactive dashboard next to the site page. A failure here is a warning, never a break. */
    private void writeDashboard(AnalysisReport analysed) {
        Path directory = getReportOutputDirectory().toPath();
        Path file = directory.resolve(DASHBOARD_NAME);
        try {
            Files.createDirectories(directory);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                new HtmlReportExporter().export(analysed, writer);
            }
            getLog().info("Wrote AEM Code Analyser dashboard to " + file);
        } catch (IOException | io.github.rohithv16.aem.analyser.core.export.ExportException e) {
            getLog().warn("AEM Code Analyser: could not write the dashboard to " + file + ": "
                    + e.getMessage());
        }
    }

    private void logSummary(AnalysisReport analysed) {
        long errors = analysed.countBySeverity(Severity.ERROR);
        long warnings = analysed.countBySeverity(Severity.WARNING);
        getLog().info(String.format(Locale.ROOT,
                "AEM Code Analyser: %d violation(s) (%d errors, %d warnings) for the site report",
                analysed.violations().size(), errors, warnings));
    }

    /**
     * Renders the sink page. Package-visible and pure so the empty-report case can be asserted
     * without running Maven: an empty report must produce a page saying so, not an exception and not
     * a stack trace inside the site.
     */
    void render(Sink sink, AnalysisReport analysed) {
        sink.body();
        sink.section1();
        sink.sectionTitle1();
        sink.text("AEM Code Analyser");
        sink.sectionTitle1_();

        if (analysisFailure != null) {
            sink.paragraph();
            sink.bold();
            sink.text("The analysis could not run: ");
            sink.bold_();
            sink.text(analysisFailure);
            sink.paragraph_();
        }

        sink.paragraph();
        sink.text(analysed.gateVerdict().render());
        sink.paragraph_();

        sink.section2();
        sink.sectionTitle2();
        sink.text("Findings by severity");
        sink.sectionTitle2_();
        renderSeverityTable(sink, analysed);
        sink.section2_();

        sink.section2();
        sink.sectionTitle2();
        sink.text("Findings by rule");
        sink.sectionTitle2_();
        List<RuleCount> rules = ruleCounts(analysed);
        if (rules.isEmpty()) {
            sink.paragraph();
            sink.text("No findings in this run.");
            sink.paragraph_();
        } else {
            renderRuleTable(sink, rules);
        }
        sink.section2_();

        sink.paragraph();
        sink.text("Every finding, with location, message and remediation, is listed in the ");
        sink.link(DASHBOARD_NAME);
        sink.text("full AEM Code Analyser dashboard");
        sink.link_();
        sink.text(".");
        sink.paragraph_();

        sink.section1_();
        sink.body_();
    }

    private void renderSeverityTable(Sink sink, AnalysisReport analysed) {
        sink.table();
        sink.tableRows(new int[]{Sink.JUSTIFY_LEFT, Sink.JUSTIFY_RIGHT}, true);
        sink.tableRow();
        sink.tableHeaderCell();
        sink.text("Severity");
        sink.tableHeaderCell_();
        sink.tableHeaderCell();
        sink.text("Count");
        sink.tableHeaderCell_();
        sink.tableRow_();
        for (Severity severity : Severity.values()) {
            sink.tableRow();
            sink.tableCell();
            sink.text(severity.name());
            sink.tableCell_();
            sink.tableCell();
            sink.text(Long.toString(analysed.countBySeverity(severity)));
            sink.tableCell_();
            sink.tableRow_();
        }
        sink.tableRows_();
        sink.table_();
    }

    private void renderRuleTable(Sink sink, List<RuleCount> rules) {
        sink.table();
        sink.tableRows(new int[]{Sink.JUSTIFY_LEFT, Sink.JUSTIFY_LEFT, Sink.JUSTIFY_RIGHT}, true);
        sink.tableRow();
        sink.tableHeaderCell();
        sink.text("Rule");
        sink.tableHeaderCell_();
        sink.tableHeaderCell();
        sink.text("Description");
        sink.tableHeaderCell_();
        sink.tableHeaderCell();
        sink.text("Findings");
        sink.tableHeaderCell_();
        sink.tableRow_();
        for (RuleCount rule : rules) {
            sink.tableRow();
            sink.tableCell();
            sink.link(RULE_DOCS + rule.ruleId().toLowerCase(Locale.ROOT));
            sink.text(rule.ruleId());
            sink.link_();
            sink.tableCell_();
            sink.tableCell();
            sink.text(rule.description());
            sink.tableCell_();
            sink.tableCell();
            sink.text(Long.toString(rule.count()));
            sink.tableCell_();
            sink.tableRow_();
        }
        sink.tableRows_();
        sink.table_();
    }

    /**
     * One row per rule that fired, most frequent first, with the registry's own description so the
     * page never carries a second, drifting copy of the rule metadata.
     */
    static List<RuleCount> ruleCounts(AnalysisReport analysed) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Violation violation : analysed.violations()) {
            counts.merge(violation.ruleId() == null ? "unknown" : violation.ruleId(), 1, Integer::sum);
        }
        List<RuleCount> rules = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            rules.add(new RuleCount(entry.getKey(), entry.getValue(),
                    io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry
                            .get(entry.getKey()).shortDescription()));
        }
        rules.sort(Comparator.comparingInt(RuleCount::count).reversed()
                .thenComparing(RuleCount::ruleId));
        return rules;
    }

    /** One rule's contribution to the page. */
    record RuleCount(String ruleId, int count, String description) {
    }
}
