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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
            if (!isServlet(component)) {
                continue;
            }

            ServletRegistration reg = parseServletRegistration(component);
            for (String path : reg.paths) {
                pathMap.computeIfAbsent(path, k -> new ArrayList<>()).add(reg);
            }
            if (!reg.resourceTypes.isEmpty()) {
                for (String rt : reg.resourceTypes) {
                    String key = rt + "|" + String.join(",", reg.selectors) + "|" +
                            String.join(",", reg.extensions) + "|" + String.join(",", reg.methods);
                    resourceTypeMap.computeIfAbsent(key, k -> new ArrayList<>()).add(reg);
                }
            }
        }

        // Check path conflicts
        for (Map.Entry<String, List<ServletRegistration>> entry : pathMap.entrySet()) {
            String path = entry.getKey();
            List<ServletRegistration> list = entry.getValue();
            if (list.size() > 1 && hasRankingConflict(list)) {
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
                            "DUPLICATE_SERVLET_PATH",
                            String.format("Servlet '%s' conflicts with '%s': both claim sling.servlet.paths = '%s' with identical service.ranking (%d).",
                                    other.component.getComponentClassFqn(), first.component.getComponentClassFqn(), path, other.ranking),
                            relativePath(context, other.component.getFilePath()),
                            new TextRegion(other.component.getSourceLocation().line(), 1,
                                    other.component.getSourceLocation().line(), 1),
                            path,
                            "Differentiate servlet paths or assign explicit service.ranking to define precedence.",
                            related
                    ));
                }
            }
        }

        // Check resourceType conflicts
        for (Map.Entry<String, List<ServletRegistration>> entry : resourceTypeMap.entrySet()) {
            List<ServletRegistration> list = entry.getValue();
            if (list.size() > 1 && hasRankingConflict(list)) {
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
                            "DUPLICATE_RESOURCE_TYPE_SERVLET",
                            String.format("Servlet '%s' conflicts with '%s': both claim identical resourceType mapping with identical service.ranking (%d).",
                                    other.component.getComponentClassFqn(), first.component.getComponentClassFqn(), other.ranking),
                            relativePath(context, other.component.getFilePath()),
                            new TextRegion(other.component.getSourceLocation().line(), 1,
                                    other.component.getSourceLocation().line(), 1),
                            entry.getKey(),
                            "Differentiate selectors/methods or define explicit service.ranking.",
                            related
                    ));
                }
            }
        }

        return violations;
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
        int firstRanking = list.get(0).ranking;
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i).ranking == firstRanking) {
                return true;
            }
        }
        return false;
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
                } catch (NumberFormatException ignored) {}
            }
        }

        Collections.sort(selectors);
        Collections.sort(extensions);
        Collections.sort(methods);

        return new ServletRegistration(component, paths, resourceTypes, selectors, extensions, methods, ranking);
    }

    private List<String> extractValues(String raw) {
        return Arrays.stream(raw.split("[,;]"))
                .map(String::trim)
                .map(s -> s.replaceAll("^\"|\"$", ""))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private String relativePath(RuleContext context, Path path) {
        if (path == null) {
            return "";
        }
        try {
            return context.projectRoot().relativize(path).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
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
