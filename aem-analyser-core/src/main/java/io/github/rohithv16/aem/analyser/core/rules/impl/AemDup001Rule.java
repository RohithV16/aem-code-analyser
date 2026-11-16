package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule AEM-DUP-001 — Duplicate / Conflicting Servlet Endpoints.
 *
 * <p>Detects when two or more OSGi servlets register to the exact same URL path or
 * resourceType/selector/extension/method mapping with identical service ranking.
 * In Apache Sling, this creates silent runtime hijacking where only one servlet handles requests
 * depending on bundle startup ordering.
 */
public final class AemDup001Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_DUP_001.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        Map<String, List<ServletRegistration>> pathMap = new HashMap<>();
        Map<String, List<ServletRegistration>> resourceTypeMap = new HashMap<>();

        for (OSGiComponentNode component : context.components()) {
            if (isServlet(component)) {
                registerServlet(component, pathMap, resourceTypeMap);
            }
        }

        collectConflicts(context, pathMap, "DUPLICATE_SERVLET_PATH",
                "Differentiate servlet paths or assign explicit service.ranking to define precedence.",
                (key, first, other) -> String.format(
                        "Servlet '%s' conflicts with '%s': both claim sling.servlet.paths = '%s'"
                                + " with identical service.ranking (%d).",
                        other.component.getComponentClassFqn(),
                        first.component.getComponentClassFqn(), key, other.ranking),
                violations);
        collectConflicts(context, resourceTypeMap, "DUPLICATE_RESOURCE_TYPE_SERVLET",
                "Differentiate selectors/methods or define explicit service.ranking.",
                (key, first, other) -> String.format(
                        "Servlet '%s' conflicts with '%s': both claim identical resourceType mapping"
                                + " with identical service.ranking (%d).",
                        other.component.getComponentClassFqn(),
                        first.component.getComponentClassFqn(), other.ranking),
                violations);
        return violations;
    }

    /** Indexes one servlet by every registered path and by its resourceType/selector key. */
    private void registerServlet(OSGiComponentNode component,
                                 Map<String, List<ServletRegistration>> pathMap,
                                 Map<String, List<ServletRegistration>> resourceTypeMap) {
        ServletRegistration reg = parseServletRegistration(component);
        for (String path : reg.paths) {
            pathMap.computeIfAbsent(path, k -> new ArrayList<>()).add(reg);
        }
        if (reg.resourceTypes.isEmpty()) {
            return;
        }
        for (String resourceType : reg.resourceTypes) {
            String key = resourceType + "|" + String.join(",", reg.selectors) + "|"
                    + String.join(",", reg.extensions) + "|" + String.join(",", reg.methods);
            resourceTypeMap.computeIfAbsent(key, k -> new ArrayList<>()).add(reg);
        }
    }

    /**
     * Reports every servlet that contends for the same registration key at an identical
     * {@code service.ranking}.
     */
    private void collectConflicts(RuleContext context,
                                  Map<String, List<ServletRegistration>> registrations,
                                  String subtype,
                                  String remediation,
                                  ConflictMessage messageFormat,
                                  List<Violation> violations) {
        for (Map.Entry<String, List<ServletRegistration>> entry : registrations.entrySet()) {
            List<ServletRegistration> list = entry.getValue();
            if (list.size() <= 1 || !hasRankingConflict(list)) {
                continue;
            }
            ServletRegistration first = list.get(0);
            for (int i = 1; i < list.size(); i++) {
                ServletRegistration other = list.get(i);
                List<RelatedLocation> related = List.of(new RelatedLocation(
                        "Conflicting servlet: " + first.component.getComponentClassFqn(),
                        relativePath(context, first.component.getFilePath()),
                        new TextRegion(first.component.getSourceLocation().line(), 1,
                                first.component.getSourceLocation().line(), 1)));
                violations.add(new Violation(
                        RuleId.AEM_DUP_001.id(),
                        RuleId.AEM_DUP_001.defaultLevel(),
                        subtype,
                        messageFormat.format(entry.getKey(), first, other),
                        relativePath(context, other.component.getFilePath()),
                        new TextRegion(other.component.getSourceLocation().line(), 1,
                                other.component.getSourceLocation().line(), 1),
                        entry.getKey(),
                        remediation,
                        related));
            }
        }
    }

    /** Builds the violation message for one conflicting servlet pair. */
    @FunctionalInterface
    private interface ConflictMessage {
        String format(String key, ServletRegistration first, ServletRegistration other);
    }

    private boolean isServlet(OSGiComponentNode component) {
        if (component.getServiceInterfaces().stream().anyMatch(i -> i.endsWith("Servlet"))) {
            return true;
        }
        return component.getSuperClass()
                .map(sc -> sc.contains("SlingSafeMethodsServlet") || sc.contains("SlingAllMethodsServlet") || sc.contains("HttpServlet"))
                .orElse(false);
    }

    private boolean hasRankingConflict(List<ServletRegistration> list) {
        // Group by ranking and report a conflict when ANY ranking is shared. Comparing only against
        // list.get(0) missed the common "ranked override + two accidental duplicates" shape and made
        // the result depend on discovery order.
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (ServletRegistration registration : list) {
            counts.merge(registration.ranking, 1, Integer::sum);
        }
        return counts.values().stream().anyMatch(count -> count > 1);
    }

    private ServletRegistration parseServletRegistration(OSGiComponentNode component) {
        List<String> paths = new ArrayList<>();
        List<String> resourceTypes = new ArrayList<>();
        List<String> selectors = new ArrayList<>();
        List<String> extensions = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        int ranking = 0;

        for (String prop : component.getComponentProperties()) {
            String lower = prop.toLowerCase();
            if (lower.startsWith("sling.servlet.paths=")) {
                paths.addAll(extractValues(prop.substring("sling.servlet.paths=".length())));
            } else if (lower.startsWith("sling.servlet.resourcetypes=")) {
                resourceTypes.addAll(extractValues(prop.substring("sling.servlet.resourcetypes=".length())));
            } else if (lower.startsWith("sling.servlet.selectors=")) {
                selectors.addAll(extractValues(prop.substring("sling.servlet.selectors=".length())));
            } else if (lower.startsWith("sling.servlet.extensions=")) {
                extensions.addAll(extractValues(prop.substring("sling.servlet.extensions=".length())));
            } else if (lower.startsWith("sling.servlet.methods=")) {
                methods.addAll(extractValues(prop.substring("sling.servlet.methods=".length())));
            } else if (lower.startsWith("service.ranking=")) {
                try {
                    ranking = Integer.parseInt(prop.substring("service.ranking=".length()).trim());
                } catch (NumberFormatException malformedRanking) {
                    // A non-numeric ranking keeps the default (0): the endpoint is still compared,
                    // only the ranking dimension is left at its Sling default.
                }
            }
        }

        selectors.sort(Comparator.naturalOrder());
        extensions.sort(Comparator.naturalOrder());
        methods.sort(Comparator.naturalOrder());

        return new ServletRegistration(component, paths, resourceTypes, selectors, extensions, methods, ranking);
    }

    private List<String> extractValues(String raw) {
        return Arrays.stream(raw.split("[,;]"))
                .map(String::trim)
                .map(s -> s.replaceAll("(^\")|(\"$)", ""))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private String relativePath(RuleContext context, Path path) {
        return context.relativePath(path);
    }

    private record ServletRegistration(
            OSGiComponentNode component,
            List<String> paths,
            List<String> resourceTypes,
            List<String> selectors,
            List<String> extensions,
            List<String> methods,
            int ranking
    ) {}
}
