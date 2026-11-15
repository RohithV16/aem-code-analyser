package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Rule AEM-CLOUD-002: Deprecated JSP Usage.
 * Flags legacy JSP (.jsp, .jspf) files within AEM component directories.
 * AEM as a Cloud Service deprecates JSP in favor of HTL (Sightly).
 */
public final class AemCloud002Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_CLOUD_002.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        Path root = context.projectRoot();
        if (root == null || !Files.isDirectory(root)) {
            return violations;
        }

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isCandidateJsp)
                    .forEach(jspFile -> {
                        violations.add(new Violation(
                                RuleId.AEM_CLOUD_002.id(),
                                RuleId.AEM_CLOUD_002.defaultLevel(),
                                "DEPRECATED_JSP_USAGE",
                                "Legacy JSP file '" + jspFile.getFileName()
                                        + "' detected. AEM as a Cloud Service deprecates JSP in favor of HTL (HTML Template Language).",
                                relativePath(context, jspFile),
                                TextRegion.forWholeFile(),
                                null,
                                "Migrate JSP template to modern HTL (.html) script and backing Sling Model.",
                                List.of()
                        ));
                    });
        } catch (IOException ignored) {
        }

        return violations;
    }

    private boolean isCandidateJsp(Path path) {
        String s = path.toString().replace('\\', '/');
        if (s.contains("/target/") || s.contains("/node_modules/") || s.contains("/.git/")) {
            return false;
        }
        if (!s.endsWith(".jsp") && !s.endsWith(".jspf")) {
            return false;
        }
        return s.contains("/jcr_root/") || s.contains("/apps/");
    }

    private String relativePath(RuleContext context, Path path) {
        if (path == null) {
            return "";
        }
        return context.projectRoot().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
