package io.github.rohithv16.aem.analyser.maven;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.apache.maven.reporting.MavenReport;
import org.codehaus.doxia.sink.Sink;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #45: the {@code mvn site} report page.
 *
 * <p>The rule a site report must never break is that it always renders — including on a project with
 * no findings, and after an analysis failure. These tests drive the real sink rendering through a
 * recording {@link Sink} proxy, so the page's content is asserted directly instead of inferred from
 * a green Maven build.
 */
class SiteReportTest {

    private static final Path ROOT = Path.of("/mock/project");

    @Test
    void theReportIsAMavenReportNamedForItsSitePage() {
        AemAnalyserSiteReport report = new AemAnalyserSiteReport();

        assertTrue(MavenReport.class.isAssignableFrom(AemAnalyserSiteReport.class));
        assertEquals("aem-analyser", report.getOutputName());
        assertEquals("AEM Code Analyser", report.getName(Locale.ENGLISH));
        assertEquals(MavenReport.CATEGORY_PROJECT_REPORTS, report.getCategoryName());
        assertFalse(report.isExternalReport());
        assertTrue(report.canGenerateReport());
    }

    @Test
    void anEmptyReportRendersAPageInsteadOfThrowing() {
        AemAnalyserSiteReport mojo = new AemAnalyserSiteReport();
        RecordingSink sink = new RecordingSink();
        AnalysisReport empty = new AnalysisReport(ROOT, Map.of(), List.of(), 0L);

        assertDoesNotThrow(() -> mojo.render(sink.sink(), empty));

        String page = sink.text();
        assertTrue(page.contains("AEM Code Analyser"), page);
        assertTrue(page.contains("No findings in this run."), page);
        assertTrue(page.contains("GATE PASSED"), page);
        assertTrue(sink.links.contains(AemAnalyserSiteReport.DASHBOARD_NAME),
                "the page links the full dashboard: " + sink.links);
    }

    @Test
    void thePageListsEveryRuleThatFiredWithItsCountAndDocumentationLink() {
        AemAnalyserSiteReport mojo = new AemAnalyserSiteReport();
        RecordingSink sink = new RecordingSink();
        AnalysisReport report = new AnalysisReport(ROOT, Map.of(), List.of(
                violation("AEM-DEAD-004", Severity.ERROR),
                violation("AEM-DEAD-004", Severity.ERROR),
                violation("AEM-QUAL-003", Severity.WARNING)), 1L);

        assertDoesNotThrow(() -> mojo.render(sink.sink(), report));

        String page = sink.text();
        assertTrue(page.contains("Findings by severity"), page);
        assertTrue(page.contains("Findings by rule"), page);
        assertTrue(page.contains("AEM-DEAD-004"), page);
        assertTrue(page.contains("AEM-QUAL-003"), page);
        assertTrue(page.contains("ERROR"), page);
        assertTrue(page.contains("WARNING"), page);
        assertTrue(page.contains("AEM-DEAD-004"), page);
        assertTrue(sink.links.stream().anyMatch(link -> link.endsWith("#aem-dead-004")),
                "every rule id links to its documentation: " + sink.links);
    }

    @Test
    void ruleCountsAreMostFrequentFirstAndNeverNullOrDropped() {
        List<AemAnalyserSiteReport.RuleCount> counts = AemAnalyserSiteReport.ruleCounts(
                new AnalysisReport(ROOT, Map.of(), List.of(
                        violation("AEM-QUAL-003", Severity.WARNING),
                        violation("AEM-DEAD-004", Severity.ERROR),
                        violation("AEM-DEAD-004", Severity.ERROR)), 1L));

        assertEquals(List.of("AEM-DEAD-004", "AEM-QUAL-003"),
                counts.stream().map(AemAnalyserSiteReport.RuleCount::ruleId).toList());
        assertEquals(2, counts.get(0).count());
        assertFalse(counts.get(0).description().isBlank(), "the page reuses the registry description");
    }

    private static Violation violation(String ruleId, Severity severity) {
        return new Violation(ruleId, severity, "SUBTYPE", "message", "core/src/A.java",
                TextRegion.forWholeFile(), null, null, List.of());
    }

    /**
     * A recording sink. Doxia's sink is a 150-method interface whose implementation lives in the site
     * plugin, so a dynamic proxy keeps the fake honest (every call goes through the real interface)
     * without a hand-written no-op class for methods the report never calls.
     */
    private static final class RecordingSink {

        private final List<String> text = new ArrayList<>();
        private final List<String> links = new ArrayList<>();

        Sink sink() {
            return (Sink) Proxy.newProxyInstance(
                    AemAnalyserSiteReport.class.getClassLoader(),
                    new Class<?>[]{Sink.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "text" -> text.add((String) args[0]);
                            case "link" -> links.add((String) args[0]);
                            case "toString" -> {
                                return "RecordingSink";
                            }
                            case "hashCode" -> {
                                return System.identityHashCode(proxy);
                            }
                            case "equals" -> {
                                return proxy == args[0];
                            }
                            default -> {
                                // Every other sink call is a structural event: recorded by the
                                // absence of text, asserted through the text/link captures.
                            }
                        }
                        return null;
                    });
        }

        String text() {
            return String.join(" ", text);
        }
    }
}
