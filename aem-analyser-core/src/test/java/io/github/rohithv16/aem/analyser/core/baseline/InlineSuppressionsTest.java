package io.github.rohithv16.aem.analyser.core.baseline;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineSuppressionsTest {

    @TempDir
    Path root;

    private Violation violation(String ruleId, String file, int line) {
        return new Violation(ruleId, Severity.WARNING, null, "message", file,
                new TextRegion(line, 1, line, 10), null, null, List.of());
    }

    @Test
    void nextLineDirectiveSuppressesOnlyTheNamedRule() throws Exception {
        Path file = root.resolve("Service.java");
        Files.writeString(file, """
                class Service {
                    // aem-analyser-disable-next-line AEM-DEAD-001
                    void orphaned() { }
                }
                """);

        assertTrue(InlineSuppressions.isSuppressed(root, violation("AEM-DEAD-001", "Service.java", 3)));
        assertFalse(InlineSuppressions.isSuppressed(root, violation("AEM-DUP-010", "Service.java", 3)));
    }

    /**
     * Regression: the rule-id tokenizer used to split on ':', so a directive could never name a
     * SonarJava rule and silently did nothing for the majority of real findings.
     */
    @Test
    void sonarRuleIdsCanBeSuppressed() throws Exception {
        Path file = root.resolve("Sonar.java");
        Files.writeString(file, """
                class Sonar {
                    // aem-analyser-disable-next-line java:S1166
                    void body() { }
                }
                """);

        assertTrue(InlineSuppressions.isSuppressed(root, violation("java:S1166", "Sonar.java", 3)));
        assertFalse(InlineSuppressions.isSuppressed(root, violation("java:S1192", "Sonar.java", 3)));
    }

    @Test
    void directiveQuotedInsideAStringLiteralDoesNotSuppress() throws Exception {
        Path file = root.resolve("Prose.java");
        Files.writeString(file, """
                class Prose {
                    static final String HELP = "// aem-analyser-disable-next-line AEM-DEAD-001";
                    void body() { }
                }
                """);

        assertFalse(InlineSuppressions.isSuppressed(root, violation("AEM-DEAD-001", "Prose.java", 3)));
    }

    @Test
    void directiveOnTheFindingLineDoesNotSuppressIt() throws Exception {
        Path file = root.resolve("SameLine.java");
        Files.writeString(file, """
                class SameLine {
                    void body() { } // aem-analyser-disable-next-line AEM-DEAD-001
                }
                """);

        assertFalse(InlineSuppressions.isSuppressed(root, violation("AEM-DEAD-001", "SameLine.java", 2)));
    }

    @Test
    void directiveWithoutRuleIdsSuppressesEveryRule() throws Exception {
        Path file = root.resolve("Template.html");
        Files.writeString(file, """
                <!-- aem-analyser-disable-next-line -->
                <div data-sly-use.model="com.example.Model"></div>
                """);

        assertTrue(InlineSuppressions.isSuppressed(root, violation("AEM-SEC-001", "Template.html", 2)));
        assertTrue(InlineSuppressions.isSuppressed(root, violation("AEM-DEAD-003B", "Template.html", 2)));
    }

    @Test
    void fileLevelDirectiveSuppressesNamedRuleAnywhere() throws Exception {
        Path file = root.resolve("Config.java");
        Files.writeString(file, """
                // aem-analyser-disable-file AEM-DUP-010
                class Config {
                    void body() { }
                }
                """);

        assertTrue(InlineSuppressions.isSuppressed(root, violation("AEM-DUP-010", "Config.java", 3)));
        assertFalse(InlineSuppressions.isSuppressed(root, violation("AEM-DEAD-002A", "Config.java", 3)));
    }
}
