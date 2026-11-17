package io.github.rohithv16.aem.analyser.core.rules.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleDocsGeneratorTest {

    @TempDir
    Path output;

    @Test
    void writesOneIssuesPageWithAFrontMatterLayout() throws Exception {
        RuleDocsGenerator.writeAll(output);

        Path page = output.resolve("issues/index.html");
        assertTrue(Files.isRegularFile(page), "the reference page is issues/index.html");

        String html = Files.readString(page);
        assertTrue(html.startsWith("---\nlayout: default\n"), "Jekyll needs front matter on the first line");
        assertTrue(html.contains("nav: issues"));
        assertTrue(html.contains("<h1>Issues and fixes</h1>"));
    }

    /**
     * The dashboard and SARIF {@code helpUri} both point at {@code issues/#<rule>}. A rule without
     * that anchor turns a finding into a dead link, so every registered rule must have one.
     */
    @Test
    void everyRuleHasAnAnchorThatItsHelpUriResolvesTo() throws Exception {
        RuleDocsGenerator.writeAll(output);
        String html = Files.readString(output.resolve("issues/index.html"));

        for (RuleDescriptor rule : RuleRegistry.all()) {
            String anchor = RuleDocsGenerator.anchorFor(rule.id());
            assertTrue(html.contains("id=\"" + anchor + "\""), "missing anchor for " + rule.id());
            assertTrue(rule.helpUri().endsWith("/issues/#" + anchor),
                    "helpUri for " + rule.id() + " must land on its anchor, was " + rule.helpUri());
        }
    }

    @Test
    void everyRuleShowsTheFixAndHowToSuppressIt() throws Exception {
        RuleDocsGenerator.writeAll(output);
        String html = Files.readString(output.resolve("issues/index.html"));

        for (RuleDescriptor rule : RuleRegistry.all()) {
            RuleFixCatalog.Fix fix =
                    RuleFixCatalog.forRule(io.github.rohithv16.aem.analyser.core.model.report.RuleId
                            .valueOf(rule.id().replace('-', '_')));
            String fixText = fix.steps() == null || fix.steps().isBlank() ? rule.remediation() : fix.steps();
            fixText = RuleDocsGenerator.normalise(fixText);
            assertTrue(html.contains(RuleDocsGenerator.escape(fixText)),
                    "missing fix text for " + rule.id());
            assertTrue(html.contains("aem-analyser-disable-next-line " + rule.id()),
                    "missing suppression example for " + rule.id());
        }
        assertTrue(html.contains("Why it matters"));
        assertTrue(html.contains("How to fix it"));
    }

    /** A before/after example is the point of the page: most rules must carry one. */
    @Test
    void mostRulesCarryABeforeAndAfterExample() {
        long withExamples = List.of(io.github.rohithv16.aem.analyser.core.model.report.RuleId.values()).stream()
                .map(RuleFixCatalog::forRule)
                .filter(fix -> fix.hasBadExample() && fix.hasGoodExample())
                .count();

        assertTrue(withExamples >= 20,
                "expected most rules to show a failing and a fixed example, found " + withExamples);
    }

    @Test
    void coversEveryFamilyAndTheFiltersWorkOverAllCards() throws Exception {
        RuleDocsGenerator.writeAll(output);
        String html = Files.readString(output.resolve("issues/index.html"));

        for (String family : List.of("dead", "dup", "qual", "perf", "cloud", "sec", "comp")) {
            assertTrue(html.contains("id=\"family-" + family + "\""), "missing family section: " + family);
        }
        assertEquals(RuleRegistry.all().size(), countOccurrences(html, "class=\"rule-card\""));
        assertTrue(html.contains("data-family=\"DEAD\""));
        assertTrue(html.contains("id=\"issueSearch\""));
    }

    @Test
    void escapesMarkupFromRuleText() throws Exception {
        RuleDocsGenerator.writeAll(output);
        String html = Files.readString(output.resolve("issues/index.html"));

        // HTL examples contain angle brackets; they must be escaped inside <code>.
        assertTrue(html.contains("&lt;sly data-sly-use"));
        assertFalse(html.contains("<sly data-sly-use"), "raw HTL must not leak into the page");
        assertFalse(html.contains("@ context='unsafe'"), "quotes must be escaped in attributes");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
