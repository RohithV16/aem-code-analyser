package io.github.rohithv16.aem.analyser.core.rules.metadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    static String render(RuleDescriptor rule) {
        StringBuilder doc = new StringBuilder();
        doc.append("---\nlayout: default\ntitle: ").append(rule.id()).append("\n---\n\n");
        doc.append("# ").append(rule.id()).append(" — ").append(rule.name()).append("\n\n");
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
