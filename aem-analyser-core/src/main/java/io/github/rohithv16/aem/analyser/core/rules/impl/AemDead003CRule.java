package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;
import io.github.rohithv16.aem.analyser.core.rules.catalogs.SystemEntryPointCatalog;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FEAT-RULE-05 (complementary): Rule AEM-DEAD-003C — Inactive Delayed Components.
 * Under OSGi DS 1.4 §112.4.3, a component providing no service interfaces and declaring
 * {@code immediate = false} is never activated by Declarative Services.
 */
public final class AemDead003CRule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_DEAD_003C.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new java.util.ArrayList<>();
        for (OSGiComponentNode component : context.components()) {
            if (!component.getServiceInterfaces().isEmpty()) {
                continue;
            }
            if (!component.isImmediateExplicitlySet() || component.isImmediate()) {
                continue;
            }
            Set<String> implementedTypes = new HashSet<>(component.getInterfaces());
            component.getSuperClass().ifPresent(implementedTypes::add);
            if (SystemEntryPointCatalog.isSystemEntryPoint(implementedTypes, component.getComponentProperties())) {
                continue;
            }
            var location = component.getSourceLocation();
            TextRegion region = location == null ? TextRegion.forWholeFile()
                    : new TextRegion(location.line(), location.column(),
                    location.line(), location.column() + 12);
            violations.add(new Violation(
                    RuleId.AEM_DEAD_003C.id(),
                    RuleId.AEM_DEAD_003C.defaultLevel(),
                    "INACTIVE_DELAYED_COMPONENT",
                    "Component '" + component.getComponentClassFqn()
                            + "' provides no service interfaces and immediate = false."
                            + " Declarative Services will never activate this component.",
                    relativePath(context, component.getFilePath()),
                    region,
                    component.getComponentClassFqn(),
                    "Set immediate = true, provide a service interface, or delete the component.",
                    List.of()));
        }
        return violations;
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        if (path == null) {
            return "";
        }
        return context.projectRoot().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
