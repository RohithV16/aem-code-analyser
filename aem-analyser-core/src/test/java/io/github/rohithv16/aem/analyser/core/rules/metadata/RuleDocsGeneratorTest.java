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

        assertEquals(RuleRegistry.all().size(),
                Files.list(output).filter(Files::isRegularFile).count());
        String page = Files.readString(output.resolve("AEM-DEAD-001.md"));
        assertTrue(page.contains("# AEM-DEAD-001"));
        assertTrue(page.contains("## Remediation"));
        assertTrue(page.contains("aem-analyser-disable-next-line AEM-DEAD-001"));
    }
}
