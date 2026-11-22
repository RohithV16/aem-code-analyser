package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.htl.CqComponentDefinition;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlResourceRef;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule AEM-COMP-002 — a literal resource type that names no component.
 *
 * <p>Renaming or moving a component leaves content and templates binding to the old type. AEM
 * resolves {@code sling:resourceType} at request time, so the page renders blank while the build
 * stays green. This rule compares every literal resource type in shipped content
 * ({@code .content.xml}) and in a static {@code data-sly-resource} {@code resourceType} option
 * against the component directories the project actually ships.</p>
 *
 * <p>Only the project's own namespace is judged. Platform prefixes ({@code cq/}, {@code granite/},
 * {@code core/wcm/}, {@code wcm/}, {@code foundation/}) are supplied by AEM and are skipped, as is
 * any type whose first path segment has no {@code jcr_root/apps/<segment>} directory in the
 * project — a type owned by a bundle that is not part of this reactor cannot be resolved here.
 * {@code target/} copies are never scanned.</p>
 */
public final class AemComp002Rule implements RuleEvaluator {

    /** Resource-type prefixes that AEM itself supplies; never this project's to resolve. */
    private static final List<String> PLATFORM_PREFIXES = List.of(
            "cq/", "granite/", "core/wcm/", "wcm/", "foundation/");

    private static final Pattern RESOURCE_TYPE_PATTERN = Pattern.compile(
            "sling:resourceType\\s*=\\s*([\"'])([^\"']+)\\1");

    private static final Pattern APPS_MARKER = Pattern.compile("/jcr_root/apps/(.+)$");

    @Override
    public String getRuleId() {
        return RuleId.AEM_COMP_002.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        Path root = context.projectRoot();
        if (root == null || !Files.isDirectory(root)) {
            return violations;
        }

        Set<String> componentDirs = new LinkedHashSet<>();
        Set<String> ownPrefixes = new LinkedHashSet<>();
        collectComponentDirectories(root, componentDirs, ownPrefixes);
        for (CqComponentDefinition component : context.cqComponents()) {
            if (component.resourceType() != null) {
                componentDirs.add(component.resourceType());
                String first = firstSegment(component.resourceType());
                if (first != null) {
                    ownPrefixes.add(first);
                }
            }
        }
        if (ownPrefixes.isEmpty()) {
            // No /apps tree at all: this project ships no components, so every literal type belongs
            // to someone else and cannot be judged here.
            return violations;
        }

        for (ContentResourceType reference : contentResourceTypes(root)) {
            if (isMissing(reference.resourceType(), componentDirs, ownPrefixes)) {
                violations.add(violation(context, reference.file(), reference.line(), reference.column(),
                        reference.resourceType()));
            }
        }
        for (HtlResourceRef reference : context.htlResources()) {
            if (isMissing(reference.resourceType(), componentDirs, ownPrefixes)) {
                violations.add(violation(context, reference.filePath(), reference.line(), reference.column(),
                        reference.resourceType()));
            }
        }
        return violations;
    }

    private Violation violation(RuleContext context, Path file, int line, int column, String resourceType) {
        return new Violation(
                RuleId.AEM_COMP_002.id(),
                RuleId.AEM_COMP_002.defaultLevel(),
                "MISSING_RESOURCE_TYPE",
                "Resource type '" + resourceType + "' names no component: no directory exists at"
                        + " /apps/" + resourceType + ". Content bound to a renamed or moved component"
                        + " renders an empty region at request time.",
                context.relativePath(file),
                new TextRegion(line, Math.max(1, column), line, Math.max(1, column) + 12),
                null,
                "Repoint the reference to an existing component under the project's /apps prefix, or"
                        + " restore the component directory that was renamed or moved.",
                List.of());
    }

    private static boolean isMissing(String resourceType, Set<String> componentDirs, Set<String> ownPrefixes) {
        if (resourceType == null || resourceType.isBlank()) {
            return false;
        }
        String normalized = resourceType.strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("apps/")) {
            normalized = normalized.substring("apps/".length());
        }
        if (normalized.isEmpty() || normalized.contains("${") || normalized.contains(" ")) {
            return false;
        }
        for (String platform : PLATFORM_PREFIXES) {
            if (normalized.startsWith(platform)) {
                return false;
            }
        }
        String first = firstSegment(normalized);
        if (first == null || !ownPrefixes.contains(first)) {
            return false;
        }
        return !componentDirs.contains(normalized);
    }

    private static String firstSegment(String resourceType) {
        int slash = resourceType.indexOf('/');
        return slash > 0 ? resourceType.substring(0, slash) : null;
    }

    /**
     * One walk of the project: every directory under a {@code jcr_root/apps} root is a component
     * (or a folder of them), and its path below {@code jcr_root/apps/} is a resolvable resource
     * type. {@code target/} and {@code node_modules/} trees are never entered.
     */
    private static void collectComponentDirectories(Path root, Set<String> componentDirs,
                                                    Set<String> ownPrefixes) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (name.equals("target") || name.equals("node_modules") || name.equals(".git")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String normalized = dir.toAbsolutePath().normalize().toString().replace('\\', '/');
                    Matcher matcher = APPS_MARKER.matcher(normalized);
                    if (matcher.find()) {
                        String resourceType = matcher.group(1);
                        componentDirs.add(resourceType);
                        String first = firstSegment(resourceType);
                        if (first != null) {
                            ownPrefixes.add(first);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // An unreadable subtree is reported by the analysis diagnostics; the rule degrades to
            // judging only the component directories it managed to see.
        }
    }

    private record ContentResourceType(Path file, int line, int column, String resourceType) {
    }

    private static List<ContentResourceType> contentResourceTypes(Path root) {
        List<ContentResourceType> references = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (name.equals("target") || name.equals("node_modules") || name.equals(".git")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!".content.xml".equals(file.getFileName().toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        String content = Files.readString(file);
                        Matcher matcher = RESOURCE_TYPE_PATTERN.matcher(content);
                        while (matcher.find()) {
                            references.add(new ContentResourceType(file,
                                    lineNumber(content, matcher.start()),
                                    column(content, matcher.start()),
                                    matcher.group(2).strip()));
                        }
                    } catch (IOException ignored) {
                        // Unreadable content file: skip rather than guess.
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Same degradation as above.
        }
        return references;
    }

    private static int lineNumber(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static int column(String content, int offset) {
        int newline = content.lastIndexOf('\n', offset - 1);
        return offset - newline;
    }
}
