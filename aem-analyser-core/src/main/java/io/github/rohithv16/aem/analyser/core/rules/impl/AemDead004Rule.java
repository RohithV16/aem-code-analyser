package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlUsage;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rule AEM-DEAD-004: Broken HTL Reference.
 * Flags HTL data-sly-use expressions targeting Java classes or Sling Models that do not exist in the project.
 */
public final class AemDead004Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_DEAD_004.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        if (context.htlUsages() == null || context.htlUsages().isEmpty()) {
            return violations;
        }

        Set<String> knownClasses = new HashSet<>();
        if (context.buildResult() != null) {
            if (context.buildResult().classNodes() != null) {
                knownClasses.addAll(context.buildResult().classNodes().keySet());
            }
            if (context.buildResult().modelNodes() != null) {
                knownClasses.addAll(context.buildResult().modelNodes().keySet());
                for (var model : context.buildResult().modelNodes().values()) {
                    knownClasses.addAll(model.getAdapters());
                }
            }
        }

        for (HtlUsage usage : context.htlUsages()) {
            if (!usage.isJavaClass()) {
                continue;
            }
            String className = usage.javaClassName();
            if (isPlatformClass(className)) {
                continue;
            }

            if (!knownClasses.contains(className)) {
                TextRegion region = new TextRegion(usage.line(), usage.column(), usage.line(), usage.column() + 15);
                violations.add(new Violation(
                        RuleId.AEM_DEAD_004.id(),
                        RuleId.AEM_DEAD_004.defaultLevel(),
                        "BROKEN_HTL_REFERENCE",
                        "HTL template references missing Java class or Sling Model '" + className + "' via data-sly-use.",
                        relativePath(context, usage.filePath()),
                        region,
                        null,
                        "Verify class name spelling, create the missing model class (check both src/main/java"
                                + " and content-package jcr_root sources), or update the HTL reference.",
                        List.of()
                ));
            }
        }

        return violations;
    }

    private boolean isPlatformClass(String className) {
        if (className == null) {
            return true;
        }
        return className.startsWith("com.adobe.cq.")
                || className.startsWith("org.apache.sling.")
                || className.startsWith("com.day.cq.")
                || className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jakarta.");
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        if (path == null) {
            return "";
        }
        return context.projectRoot().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
