package io.github.rohithv16.aem.analyser.core.rules.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleDocsGeneratorTest {

    @TempDir
    Path output;

    @Test
    void writesOnePagePerRuleWithRemediation() throws Exception {
        RuleDocsGenerator.writeAll(output);

        // one page per rule plus the catalogue index
        assertEquals(RuleRegistry.all().size() + 1,
                Files.list(output).filter(Files::isRegularFile).count());
        String page = Files.readString(output.resolve("AEM-DEAD-001.md"));
        assertTrue(page.contains("# AEM-DEAD-001"));
        assertTrue(page.contains("## Remediation"));
        assertTrue(page.contains("aem-analyser-disable-next-line AEM-DEAD-001"));
    }

    /**
     * The published site links to {@code rules/}, so the directory needs an index page; without it
     * every "Rule catalogue" link from the docs 404s.
     */
    @Test
    void writesCatalogueIndexCoveringEveryRule() throws Exception {
        RuleDocsGenerator.writeAll(output);

        String index = Files.readString(output.resolve("index.md"));
        for (RuleDescriptor rule : RuleRegistry.all()) {
            assertTrue(index.contains("[" + rule.id() + "](" + rule.id() + ".html)"),
                    "index is missing a link to " + rule.id());
        }
        for (String family : java.util.List.of("Dead code", "Duplication", "Quality", "Performance",
                "AEM as a Cloud Service", "Security", "Component compatibility")) {
            assertTrue(index.contains("## " + family), "index is missing the " + family + " section");
        }
        assertTrue(index.contains("layout: default"), "index needs Jekyll front matter");
    }
}
