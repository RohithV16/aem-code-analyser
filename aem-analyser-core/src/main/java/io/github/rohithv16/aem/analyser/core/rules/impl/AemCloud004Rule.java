package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AEM-CLOUD-004: imports of APIs deprecated/removed in AEM as a Cloud Service.
 */
public final class AemCloud004Rule implements RuleEvaluator {

    private static final Set<String> DEPRECATED_IMPORTS = Set.of(
            "org.apache.commons.lang.StringUtils",
            "org.apache.commons.lang.ArrayUtils",
            "org.apache.commons.lang.StringEscapeUtils",
            "com.day.cq.wcm.api.WCMMode",
            "com.day.cq.wcm.api.designer.Designer",
            "com.day.cq.wcm.api.designer.Style"
    );

    @Override
    public String getRuleId() {
        return RuleId.AEM_CLOUD_004.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (java.nio.file.Path file : context.javaTypeFiles()) {
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (!line.startsWith("import ")) {
                        continue;
                    }
                    for (String deprecated : DEPRECATED_IMPORTS) {
                        if (line.contains(deprecated)) {
                            violations.add(new Violation(
                                    RuleId.AEM_CLOUD_004.id(),
                                    RuleId.AEM_CLOUD_004.defaultLevel(),
                                    "DEPRECATED_AEM_API",
                                    "Import '" + deprecated + "' uses an API deprecated or removed in"
                                            + " AEM as a Cloud Service.",
                                    relativePath(context, file),
                                    new TextRegion(i + 1, 1, i + 1, Math.max(2, line.length())),
                                    null,
                                    "Replace '" + deprecated + "' with the supported API for AEM as a"
                                            + " Cloud Service.",
                                    List.of()));
                        }
                    }
                }
            } catch (IOException ignored) {
                // unreadable file: diagnostics are handled by the parser
            }
        }
        return violations;
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        return context.projectRoot().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
