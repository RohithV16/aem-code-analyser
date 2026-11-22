package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AEM-DEAD-005: client library categories that no template or other clientlib references.
 *
 * <p>Files are scanned one at a time (with a size cap) so large monorepos cannot exhaust the heap.
 */
public final class AemDead005Rule implements RuleEvaluator {

    private static final Pattern CATEGORY_ATTR = Pattern.compile(
            "categories\\s*=\\s*\"?\\[([^\\]]*)\\]\"?");
    private static final Pattern DEPENDENCY_ATTR = Pattern.compile(
            "(?:dependencies|embed)\\s*=\\s*\"?\\[([^\\]]*)\\]\"?");
    private static final long MAX_SCAN_BYTES = 5L * 1024 * 1024;

    @Override
    public String getRuleId() {
        return RuleId.AEM_DEAD_005.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        Path root = context.projectRoot();
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }

        // Pass 1: collect clientlibs and their declared categories (small).
        // Categories are aggregated to their FIRST defining file so an orphaned category is reported
        // once, not once per clientlib that happens to declare it.
        Map<String, Path> firstFileByCategory = new LinkedHashMap<>();
        Set<String> allCategories = new LinkedHashSet<>();
        Set<String> referenced = new LinkedHashSet<>();
        for (Path path : context.fileInventory().filesUnder(root, AemDead005Rule::isClientlibDefinition)) {
            for (String category : categories(path, CATEGORY_ATTR)) {
                firstFileByCategory.putIfAbsent(category, path);
                allCategories.add(category);
            }
            referenced.addAll(categories(path, DEPENDENCY_ATTR));
        }
        if (allCategories.isEmpty()) {
            return List.of();
        }

        // Pass 2: stream templates/component XML one file at a time and mark referenced categories.
        for (Path path : context.fileInventory().filesUnder(root, AemDead005Rule::isTemplate)) {
            markReferences(path, allCategories, referenced);
        }

        List<Violation> violations = new ArrayList<>();
        for (Map.Entry<String, Path> entry : firstFileByCategory.entrySet()) {
            String category = entry.getKey();
            if (isPlatformCategory(category) || referenced.contains(category)) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_DEAD_005.id(),
                    Severity.INFO,
                    "ORPHANED_CLIENTLIB_CATEGORY",
                    "Client library category '" + category + "' is never referenced by a template"
                            + " or another client library.",
                    root.relativize(entry.getValue()).toString().replace('\\', '/'),
                    TextRegion.forWholeFile(),
                    category,
                    "Reference the category from an HTL/JSP template (clientlib call)"
                            + " or delete the client library.",
                    List.of()));
        }
        return violations;
    }

    private static boolean isClientlibDefinition(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return path.getFileName().toString().equals(".content.xml")
                && (normalized.contains("/clientlib") || normalized.contains("/clientLib"))
                && !isIgnored(normalized);
    }

    /** Build output, frontend dependencies and VCS metadata are never AEM content. */
    private static boolean isIgnored(String normalizedPath) {
        return normalizedPath.contains("/target/") || normalizedPath.contains("/node_modules/")
                || normalizedPath.contains("/.git/") || normalizedPath.contains("/dist/")
                || normalizedPath.contains("/.idea/") || normalizedPath.contains("/.vscode/");
    }

    private static boolean isTemplate(Path path) {
        if (isClientlibDefinition(path)) {
            return false;
        }
        String name = path.getFileName().toString();
        String normalized = path.toString().replace('\\', '/');
        if (isIgnored(normalized)) {
            return false;
        }
        return name.endsWith(".html") || name.endsWith(".jsp") || name.endsWith(".js")
                || name.endsWith(".xml");
    }

    private void markReferences(Path file, Set<String> categories, Set<String> referenced) {
        try {
            if (Files.size(file) > MAX_SCAN_BYTES) {
                return;
            }
            // Categories are normalised to lower case, so the haystack must be lower-cased too -
            // otherwise any camelCase category could never be matched and was always "orphaned".
            String content = Files.readString(file).toLowerCase(Locale.ROOT);
            for (String category : categories) {
                if (!referenced.contains(category) && content.contains(category)) {
                    referenced.add(category);
                }
            }
        } catch (IOException ignored) {
            // unreadable template: skip
        }
    }

    private List<String> categories(Path clientlib, Pattern pattern) {
        try {
            if (Files.size(clientlib) > MAX_SCAN_BYTES) {
                return List.of();
            }
            Matcher matcher = pattern.matcher(Files.readString(clientlib));
            List<String> categories = new ArrayList<>();
            while (matcher.find()) {
                for (String token : matcher.group(1).split(",")) {
                    String category = token.strip().toLowerCase(Locale.ROOT);
                    if (!category.isBlank()) {
                        categories.add(category);
                    }
                }
            }
            return categories;
        } catch (IOException e) {
            return List.of();
        }
    }

    private boolean isPlatformCategory(String category) {
        return category.startsWith("cq.") || category.startsWith("granite.")
                || category.startsWith("foundation.") || category.startsWith("core.wcm.components.");
    }
}
