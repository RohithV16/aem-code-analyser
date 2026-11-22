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
    void everyRuleGetsItsOwnIndexablePageWithAUniqueTitle() throws Exception {
        RuleDocsGenerator.writeAll(output);

        List<RuleDescriptor> rules = RuleRegistry.all();
        List<String> titles = new java.util.ArrayList<>();
        List<Path> pages = new java.util.ArrayList<>();
        for (RuleDescriptor rule : rules) {
            Path page = output.resolve("issues").resolve(RuleDocsGenerator.anchorFor(rule.id()))
                    .resolve("index.html");
            assertTrue(Files.isRegularFile(page), "missing page for " + rule.id());
            pages.add(page);

            String html = Files.readString(page);
            assertTrue(html.startsWith("---\nlayout: default\n"),
                    "the rule page needs front matter so Jekyll renders and indexes it: " + rule.id());
            assertTrue(html.contains("nav: issues"));
            assertTrue(html.contains("rule_id: \"" + rule.id() + "\""), "page must carry its rule id");
            assertTrue(html.contains("<h1><span class=\"rule-card__id\">" + rule.id() + "</span>"),
                    "the id must be the page's own heading");

            String title = frontMatter(html, "title");
            assertTrue(title.startsWith(rule.id() + " "), "title must lead with the id: " + title);
            titles.add(title);

            String description = frontMatter(html, "description");
            assertTrue(description.length() >= 40, "empty meta description for " + rule.id());
            assertTrue(description.length() <= 160, "description is too long for a snippet: " + rule.id());
        }

        assertEquals(rules.size(), pages.size());
        assertEquals(pages.size(), new java.util.HashSet<>(titles).size(),
                "two rule pages share a <title>, which is what the single index page did wrong");
    }

    /** The index is the filterable catalogue; a card must lead to the page that owns its rule. */
    @Test
    void everyIndexCardLinksToItsRulePageAndBack() throws Exception {
        RuleDocsGenerator.writeAll(output);
        String index = Files.readString(output.resolve("issues/index.html"));

        for (RuleDescriptor rule : RuleRegistry.all()) {
            String anchor = RuleDocsGenerator.anchorFor(rule.id());
            assertTrue(index.contains("href=\"" + anchor + "/\""),
                    "the index card for " + rule.id() + " must link to its page");
            assertTrue(Files.readString(output.resolve("issues").resolve(anchor).resolve("index.html"))
                    .contains("href=\"../\""), "the rule page must link back to the index");
            assertEquals("https://rohithv16.github.io/aem-code-analyser/issues/" + anchor + "/",
                    RuleDocsGenerator.urlFor(rule.id()));
        }
    }

    @Test
    void aRetiredRulePageIsPruned() throws Exception {
        RuleDocsGenerator.writeAll(output);
        Path stale = output.resolve("issues/aem-dead-999");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("index.html"), "---\nlayout: default\n---\nold\n");

        RuleDocsGenerator.writeAll(output);

        assertFalse(Files.exists(stale), "a page for a rule that is no longer in the registry must go");
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
    void everyRuleEntryLinksItsTuningFlagsAndTwoSiblings() throws Exception {
        RuleDocsGenerator.writeAll(output);
        String index = Files.readString(output.resolve("issues/index.html"));

        for (RuleDescriptor rule : RuleRegistry.all()) {
            String anchor = RuleDocsGenerator.anchorFor(rule.id());
            String page = Files.readString(output.resolve("issues").resolve(anchor).resolve("index.html"));

            // Every entry, on the index and on its own page, routes to the four ways to tune it.
            for (String target : List.of("configuration", "flags", "suppression", "baseline")) {
                assertEquals(1, countOccurrences(page, "reference/#" + target),
                        rule.id() + " page must link reference/#" + target + " exactly once");
            }
            assertEquals(RuleRegistry.all().size(), countOccurrences(index, "reference/#suppression"),
                    "every index card needs the tuning row");

            // A sibling is a real page: the href must resolve to a directory this generator wrote.
            String siblings = page.substring(page.indexOf("class=\"rule-card__siblings\""));
            siblings = siblings.substring(0, siblings.indexOf("</p>"));
            List<String> hrefs = java.util.regex.Pattern.compile("href=\"(\\.\\./[a-z0-9-]+/)\"")
                    .matcher(siblings).results().map(match -> match.group(1)).toList();
            assertFalse(hrefs.isEmpty(), rule.id() + " has no sibling link");
            for (String href : hrefs) {
                String sibling = href.replace("../", "").replace("/", "");
                assertFalse(sibling.equals(anchor), rule.id() + " links to itself as a sibling");
                assertTrue(Files.isRegularFile(output.resolve("issues").resolve(sibling).resolve("index.html")),
                        rule.id() + " links to a missing sibling page: " + href);
            }
        }
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

    private static String frontMatter(String html, String key) {
        int end = html.indexOf("\n---", 3);
        String frontMatter = end < 0 ? "" : html.substring(0, end);
        for (String line : frontMatter.split("\n")) {
            if (line.startsWith(key + ": ")) {
                String value = line.substring(key.length() + 2).trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1)
                            .replace("\\\"", "\"").replace("\\\\", "\\");
                }
                return value;
            }
        }
        return "";
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
