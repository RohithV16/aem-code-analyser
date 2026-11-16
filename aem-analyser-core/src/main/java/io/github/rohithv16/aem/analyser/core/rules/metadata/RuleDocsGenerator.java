package io.github.rohithv16.aem.analyser.core.rules.metadata;

import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.rules.RuleCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Generates one Markdown documentation page per registered rule, so {@code RuleDescriptor.helpUri}
 * always resolves to real documentation.
 */
public final class RuleDocsGenerator {

    private RuleDocsGenerator() {
    }

    public static void writeAll(Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        for (RuleDescriptor rule : RuleRegistry.all()) {
            Files.writeString(outputDirectory.resolve(rule.id() + ".md"), render(rule), StandardCharsets.UTF_8);
        }
    }

    /**
     * Notes for rule ids that have no {@code RuleEvaluator} in {@link RuleCatalog}. Generated rather
     * than hand-written so {@code --generate-rule-docs} reproduces the committed pages byte for byte.
     */
    private static final Map<String, String> PRODUCER_NOTES = Map.of(
            RuleId.AEM_QUAL_006.id(),
            "> **Retired as a producer.** This check is a strict subset of AEM-QUAL-004 and reported every\n"
                    + "> resolver/session field twice with a different severity, so no rule evaluator is registered for\n"
                    + "> this id any more. The ID is kept in `RuleId`/`RuleRegistry` so existing baselines and SARIF\n"
                    + "> consumers keep working; AEM-QUAL-004 (ERROR) is now the single owner of the check.",
            RuleId.AEM_DUP_010.id(),
            "> **Produced by the duplication engine.** Findings for this id come from the PMD CPD scan\n"
                    + "(`--max-duplication`), not from a `RuleEvaluator`, so it has no inline-suppression hook.");

    static String render(RuleDescriptor rule) {
        StringBuilder doc = new StringBuilder();
        doc.append("---\nlayout: default\ntitle: ").append(rule.id()).append("\n---\n\n");
        doc.append("# ").append(rule.id()).append(" — ").append(rule.name()).append("\n\n");
        String note = PRODUCER_NOTES.get(rule.id());
        if (note != null) {
            doc.append(note).append("\n\n");
        }
        doc.append("| Property | Value |\n|---|---|\n");

        doc.append("| Severity | ").append(rule.defaultSeverity()).append(" |\n");
        doc.append("| Type | ").append(rule.type()).append(" |\n");
        doc.append("| Effort | ").append(rule.effortMinutes()).append(" minutes |\n");
        doc.append("| Tags | ").append(String.join(", ", rule.tags())).append(" |\n");
        if (rule.cwe() != null) {
            doc.append("| CWE | ").append(rule.cwe()).append(" |\n");
        }
        doc.append("\n## Description\n\n").append(rule.fullDescription()).append("\n\n");
        doc.append("## Remediation\n\n").append(rule.remediation()).append("\n\n");
        doc.append("## Configuration\n\n");
        doc.append("Enable or disable this rule through the project configuration or baseline it with\n");
        doc.append("`--update-baseline`. Inline suppression:\n\n");
        doc.append("```java\n// aem-analyser-disable-next-line ").append(rule.id()).append("\n```\n");
        return doc.toString();
    }

    public static void main(String[] args) throws IOException {
        Path output = args.length > 0 ? Path.of(args[0]) : Path.of("docs/rules");
        writeAll(output);
        System.out.println("Wrote " + RuleRegistry.all().size() + " rule pages to " + output);
    }
}
