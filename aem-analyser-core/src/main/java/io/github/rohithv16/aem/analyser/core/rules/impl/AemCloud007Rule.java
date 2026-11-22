package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.vault.VaultFilterParser;
import io.github.rohithv16.aem.analyser.core.parser.vault.VaultFilterParser.FilterRoot;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AEM-CLOUD-007: content-package filter roots shipped by more than one module.
 *
 * <p>Two packages that filter the same JCR path install over each other in the order Cloud Manager
 * happens to build them, so content silently disappears from the deployed environment and nothing in
 * either package looks wrong. The rule names the shared root, the modules that share it, and the
 * {@code mode="replace"} case that makes the overwrite explicit. A root at a platform path (bare
 * {@code /apps}, {@code /content/dam}, anything under {@code /libs}) is reported as well: those are
 * owned by the product or by another team's package.
 *
 * <p>Overlap is cross-module only. A module may legitimately root {@code /content/acme} and then add
 * a narrower root such as {@code /content/acme/image.jpg} with a blanket exclude — that is one
 * package's own filter composition, not an install-order race.
 */
public final class AemCloud007Rule implements RuleEvaluator {

    /** Roots owned by the product or shared by every project, never by this project's packages. */
    private static final Set<String> PLATFORM_ROOTS =
            Set.of("/apps", "/content", "/content/dam", "/conf", "/etc", "/libs");

    @Override
    public String getRuleId() {
        return RuleId.AEM_CLOUD_007.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        Path root = context.projectRoot();
        if (root == null) {
            return List.of();
        }
        List<FilterRoot> filters = new VaultFilterParser().scan(root);
        if (filters.isEmpty()) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        Map<String, List<FilterRoot>> byRoot = groupByRoot(filters);
        for (Map.Entry<String, List<FilterRoot>> entry : byRoot.entrySet()) {
            List<FilterRoot> sharing = entry.getValue();
            Set<String> modules = modulesOf(sharing);
            if (modules.size() < 2) {
                continue;
            }
            violations.addAll(overlapFindings(context, sharing, modules));
            violations.addAll(replaceFindings(context, sharing, modules));
        }
        violations.addAll(namespaceFindings(context, filters));
        return violations;
    }

    private Map<String, List<FilterRoot>> groupByRoot(List<FilterRoot> filters) {
        Map<String, List<FilterRoot>> byRoot = new LinkedHashMap<>();
        for (FilterRoot filter : filters) {
            byRoot.computeIfAbsent(normalize(filter.root()), key -> new ArrayList<>()).add(filter);
        }
        return byRoot;
    }

    private Set<String> modulesOf(List<FilterRoot> filters) {
        return filters.stream().map(FilterRoot::module)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** One finding per extra module, attached to that module's filter and pointing at the first one. */
    private List<Violation> overlapFindings(RuleContext context, List<FilterRoot> sharing,
                                            Set<String> modules) {
        List<Violation> violations = new ArrayList<>();
        FilterRoot first = sharing.get(0);
        Set<String> reported = new LinkedHashSet<>();
        reported.add(first.module());
        for (FilterRoot filter : sharing) {
            if (!reported.add(filter.module())) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_CLOUD_007.id(),
                    RuleId.AEM_CLOUD_007.defaultLevel(),
                    "OVERLAPPING_FILTER_ROOT",
                    "Filter root '" + normalize(filter.root()) + "' is shipped by "
                            + String.join(", ", modules)
                            + "; the package installed last overwrites the other's content.",
                    context.relativePath(filter.file()),
                    TextRegion.forLine(filter.line()),
                    null,
                    "Give each module a disjoint filter root, or move the shared paths into the module "
                            + "that should own them.",
                    List.of(new RelatedLocation(
                            "Also shipped by module '" + first.module() + "'",
                            context.relativePath(first.file()),
                            TextRegion.forLine(first.line())))));
        }
        return violations;
    }

    private List<Violation> replaceFindings(RuleContext context, List<FilterRoot> sharing,
                                            Set<String> modules) {
        List<Violation> violations = new ArrayList<>();
        for (FilterRoot filter : sharing) {
            if (!"replace".equals(filter.mode())) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_CLOUD_007.id(),
                    RuleId.AEM_CLOUD_007.defaultLevel(),
                    "REPLACE_ON_SHARED_ROOT",
                    "Filter root '" + normalize(filter.root()) + "' is shared by "
                            + String.join(", ", modules)
                            + " and uses mode=\"replace\", so this package deletes the content the "
                            + "other packages installed.",
                    context.relativePath(filter.file()),
                    TextRegion.forLine(filter.line()),
                    null,
                    "Change the shared root to mode=\"merge\", or split the root so only this package "
                            + "owns it.",
                    List.of()));
        }
        return violations;
    }

    private List<Violation> namespaceFindings(RuleContext context, List<FilterRoot> filters) {
        List<Violation> violations = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();
        for (FilterRoot filter : filters) {
            String path = normalize(filter.root());
            if (!outsideProjectNamespace(path) || !reported.add(path)) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_CLOUD_007.id(),
                    RuleId.AEM_CLOUD_007.defaultLevel(),
                    "ROOT_OUTSIDE_PROJECT_NAMESPACE",
                    "Filter root '" + path + "' is a platform path, not a project namespace; the package "
                            + "ships or replaces content it does not own.",
                    context.relativePath(filter.file()),
                    TextRegion.forLine(filter.line()),
                    null,
                    "Filter the project's own subtree (for example /apps/<project>, /content/<project>, "
                            + "/conf/<project>, /content/dam/<project>) instead of the platform root.",
                    List.of()));
        }
        return violations;
    }

    private boolean outsideProjectNamespace(String path) {
        return PLATFORM_ROOTS.contains(path) || path.startsWith("/libs/");
    }

    /** JCR paths compare without a trailing slash; "/" stays "/". */
    private static String normalize(String root) {
        String path = root == null ? "" : root.strip();
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
