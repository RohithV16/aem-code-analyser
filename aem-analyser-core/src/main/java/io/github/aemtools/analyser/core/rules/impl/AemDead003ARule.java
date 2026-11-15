package io.github.aemtools.analyser.core.rules.impl;

import io.github.aemtools.analyser.core.discovery.ExportPackageCollector;
import io.github.aemtools.analyser.core.model.graph.EdgeType;
import io.github.aemtools.analyser.core.model.graph.TextRegion;
import io.github.aemtools.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.aemtools.analyser.core.model.report.RuleId;
import io.github.aemtools.analyser.core.model.report.Violation;
import io.github.aemtools.analyser.core.rules.RuleContext;
import io.github.aemtools.analyser.core.rules.RuleEvaluator;
import io.github.aemtools.analyser.core.rules.catalogs.SystemEntryPointCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FEAT-RULE-05: Rule AEM-DEAD-003A — Unused OSGi Services.
 * Evaluates service interface package exports and the tightened {@code immediate = true}
 * semantics: unused business services never escape analysis because of immediate activation.
 */
public final class AemDead003ARule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_DEAD_003A.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (OSGiComponentNode component : context.components()) {
            if (component.getServiceInterfaces().isEmpty()) {
                continue;
            }
            if (context.ignoredServices().contains(component.getComponentClassFqn())) {
                continue;
            }
            if (context.graph().inDegree(component, EdgeType.INJECTS_SERVICE) > 0) {
                continue;
            }
            Set<String> implementedTypes = new HashSet<>(component.getServiceInterfaces());
            component.getSuperClass().ifPresent(implementedTypes::add);
            component.getInterfaces().forEach(implementedTypes::add);
            if (SystemEntryPointCatalog.isSystemEntryPoint(implementedTypes, component.getComponentProperties())) {
                continue;
            }
            // Exemption 2: at least one exposed service interface is exported
            boolean anyExported = component.getServiceInterfaces().stream()
                    .anyMatch(iface -> ExportPackageCollector.isPackageExported(
                            packageOf(iface), context.exportedPackages()));
            if (anyExported) {
                continue;
            }
            var location = component.getSourceLocation();
            TextRegion region = location == null ? TextRegion.forWholeFile()
                    : new TextRegion(location.line(), location.column(),
                    location.line(), location.column() + 12);
            violations.add(new Violation(
                    RuleId.AEM_DEAD_003A.id(),
                    RuleId.AEM_DEAD_003A.defaultLevel(),
                    "UNUSED_OSGI_SERVICE",
                    "Unused OSGi Service: component '" + component.getComponentClassFqn()
                            + "' exposes services " + component.getServiceInterfaces()
                            + " with zero @Reference injections across all bundles; none of its service"
                            + " interfaces reside in an exported package.",
                    relativePath(context, component.getFilePath()),
                    region,
                    component.getComponentClassFqn(),
                    "Delete the component and its service interface, inject the service somewhere, or"
                            + " declare the interface package as exported if consumed externally.",
                    List.of()));
        }
        return violations;
    }

    private String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot > 0 ? fqn.substring(0, dot) : "";
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        if (path == null) {
            return "";
        }
        return context.projectRoot().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
