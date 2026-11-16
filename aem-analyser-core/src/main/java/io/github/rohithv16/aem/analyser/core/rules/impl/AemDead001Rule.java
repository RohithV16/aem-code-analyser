package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphIndex;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiOcdNode;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;
import io.github.rohithv16.aem.analyser.core.rules.catalogs.PlatformPidCatalog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * FEAT-RULE-01: Rule AEM-DEAD-001 — Orphaned OSGi Configurations.
 *
 * <p>Detection predicate: {@code p ∉ P_Comp ∧ p ∉ PlatformPidCatalog ∧ p ∉ ExternalDependencyPids}.
 * Implements OSGi DS 1.4 §112.8.2 {@code ActivePids(K)} with default PID suppression and
 * {@code "$"} wildcard expansion. Sub-violations: ORPHANED_OCD_NAMED_CONFIG,
 * IGNORED_CONFIGURATION_POLICY, ORPHANED_CONFIGURATION.
 */
public final class AemDead001Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_DEAD_001.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        // P_Comp: complete set of active application component PIDs
        Set<String> activePids = new HashSet<>();
        for (OSGiComponentNode component : context.components()) {
            activePids.addAll(ReferenceGraphIndex.computeActivePids(component));
        }

        List<Violation> violations = new ArrayList<>();
        for (OSGiConfigNode config : context.configs()) {
            String pid = config.getPid();
            if (pid == null) {
                continue;
            }
            if (activePids.contains(pid)
                    || PlatformPidCatalog.isPlatformPid(pid)
                    || context.isExternalPid(pid)) {
                continue;
            }

            String violationSubtype;
            String message;
            String remediation;
            List<RelatedLocation> related = new ArrayList<>();

            OSGiOcdNode namedOcd = context.index().findOcd(pid);
            OSGiComponentNode matchingComponent = findComponentNamed(context, pid);

            if (namedOcd != null) {
                violationSubtype = "ORPHANED_OCD_NAMED_CONFIG";
                List<String> designators = designatingComponents(context, namedOcd);
                message = "Orphaned OSGi Configuration: the PID '" + pid
                        + "' names an @ObjectClassDefinition interface, but no active @Component declares"
                        + " configurationPid = \"" + pid + "\". OSGi ConfigurationAdmin never binds this file.";
                if (designators.isEmpty()) {
                    remediation = "Rename the configuration file to target the consuming component's PID, or add"
                            + " @Component(configurationPid = \"" + pid + "\") to the designating component.";
                } else {
                    remediation = "Rename the configuration file to '"
                            + String.join(".cfg.json' or '", designators)
                            + ".cfg.json', or add configurationPid = \"" + pid + "\" to @Component on: "
                            + String.join(", ", designators);
                }
                if (namedOcd.getFilePath() != null) {
                    related.add(new RelatedLocation(
                            "Target @ObjectClassDefinition interface definition.",
                            relativePath(context, namedOcd.getFilePath()),
                            regionOf(namedOcd)));
                }
            } else if (matchingComponent != null) {
                violationSubtype = "IGNORED_CONFIGURATION_POLICY";
                message = "Orphaned OSGi Configuration: component '" + matchingComponent.getId()
                        + "' declares configurationPolicy = ConfigurationPolicy.IGNORE;"
                        + " ConfigurationAdmin will never supply this configuration.";
                remediation = "Change configurationPolicy to OPTIONAL/REQUIRE on component '"
                        + matchingComponent.getId() + "', or delete the orphaned configuration file.";
                if (matchingComponent.getFilePath() != null) {
                    related.add(new RelatedLocation(
                            "Component declaring ConfigurationPolicy.IGNORE.",
                            relativePath(context, matchingComponent.getFilePath()),
                            regionOf(matchingComponent)));
                }
            } else {
                violationSubtype = "ORPHANED_CONFIGURATION";
                message = "Orphaned OSGi Configuration: No matching component, OCD-named binding,"
                        + " platform service or external dependency found for PID '" + pid + "'.";
                remediation = "Delete the configuration file, or rename it to the PID of the component"
                        + " that must consume these properties.";
            }

            Path configPath = config.getFilePath();
            if (configPath == null) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_DEAD_001.id(),
                    RuleId.AEM_DEAD_001.defaultLevel(),
                    violationSubtype,
                    message,
                    relativePath(context, configPath),
                    TextRegion.forWholeFile(),
                    pid,
                    remediation,
                    related));
        }
        return violations;
    }

    private OSGiComponentNode findComponentNamed(RuleContext context, String pid) {
        for (OSGiComponentNode component : context.components()) {
            if (pid.equals(component.getComponentClassFqn())
                    || component.getName().map(pid::equals).orElse(false)) {
                return component;
            }
        }
        return null;
    }

    private List<String> designatingComponents(RuleContext context, OSGiOcdNode ocd) {
        Set<String> names = new LinkedHashSet<>();
        for (OSGiComponentNode component : context.components()) {
            if (component.getDesignatedOcdFqn().map(ocd.getOcdFqn()::equals).orElse(false)) {
                names.add(component.getComponentClassFqn());
            }
        }
        return new ArrayList<>(names);
    }

    private String relativePath(RuleContext context, Path path) {
        return context.relativePath(path);
    }

    private TextRegion regionOf(io.github.rohithv16.aem.analyser.core.model.graph.node.AbstractPolyglotNode node) {
        var location = node.getSourceLocation();
        if (location == null) {
            return TextRegion.forWholeFile();
        }
        return new TextRegion(location.line(), location.column(), location.line(), location.column() + 12);
    }
}
