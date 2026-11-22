package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlIncludeRef;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rule AEM-DEAD-007 — broken HTL template composition.
 *
 * <p>Sightly resolves {@code data-sly-include} at request time, so a renamed, moved or mis-pathed
 * include costs nothing at build time and only shows up as an empty region on one page. This rule
 * resolves every literal include against the including template's directory and reports</p>
 *
 * <ul>
 *   <li>an include whose target file does not exist, and</li>
 *   <li>an include cycle ({@code a.html → b.html → a.html}), reported once at the edge that closes
 *       the cycle.</li>
 * </ul>
 *
 * <p>Dynamic targets ({@code ${model.tpl}}) and absolute repository paths ({@code /libs/...},
 * {@code /apps/...}) cannot be resolved against the analysed tree and are never reported.</p>
 */
public final class AemDead007Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_DEAD_007.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        List<HtlIncludeRef> includes = context.htlIncludes();
        if (includes.isEmpty()) {
            return violations;
        }

        Map<Path, List<Edge>> edgesByFile = new LinkedHashMap<>();
        Set<Path> knownFiles = new LinkedHashSet<>();
        for (HtlIncludeRef include : includes) {
            if (include.filePath() == null || include.target() == null) {
                continue;
            }
            String target = include.target().strip();
            if (!isResolvable(target)) {
                continue;
            }
            Path source = include.filePath().toAbsolutePath().normalize();
            knownFiles.add(source);
            Path resolved = source.getParent().resolve(target).normalize();
            Edge edge = new Edge(include, source, resolved);
            edgesByFile.computeIfAbsent(source, key -> new ArrayList<>()).add(edge);
            if (Files.isRegularFile(resolved)) {
                knownFiles.add(resolved);
            } else {
                violations.add(missingInclude(context, edge));
            }
        }

        violations.addAll(cycles(context, edgesByFile, knownFiles));
        return violations;
    }

    private Violation missingInclude(RuleContext context, Edge edge) {
        return new Violation(
                RuleId.AEM_DEAD_007.id(),
                RuleId.AEM_DEAD_007.defaultLevel(),
                "BROKEN_HTL_INCLUDE",
                "data-sly-include target '" + edge.reference().target() + "' does not exist relative to"
                        + " the including template; the block renders empty at request time.",
                context.relativePath(edge.source()),
                region(edge.reference()),
                null,
                "Correct the include path, or restore the template that was renamed or moved.",
                List.of());
    }

    private static Violation includeCycle(RuleContext context, Edge closingEdge, List<String> cycle) {
        return new Violation(
                RuleId.AEM_DEAD_007.id(),
                RuleId.AEM_DEAD_007.defaultLevel(),
                "HTL_INCLUDE_CYCLE",
                "data-sly-include cycle: " + String.join(" -> ", cycle)
                        + ". Sightly cannot resolve a recursive include; rendering fails or recurses"
                        + " until the request is aborted.",
                context.relativePath(closingEdge.source()),
                region(closingEdge.reference()),
                null,
                "Break the cycle by including a leaf template or guarding the recursive include.",
                List.of());
    }

    private static TextRegion region(HtlIncludeRef reference) {
        int column = Math.max(1, reference.column());
        return new TextRegion(reference.line(), column, reference.line(), column + 15);
    }

    /**
     * A literal, relative template path. Anything absolute belongs to a repository path outside the
     * analysed tree (typically {@code /libs}); anything else non-literal was already dropped by the
     * scanner.
     */
    private static boolean isResolvable(String target) {
        if (target.isEmpty() || target.startsWith("/") || target.startsWith("\\")) {
            return false;
        }
        if (target.contains("${") || target.contains("://")) {
            return false;
        }
        return true;
    }

    /**
     * Depth-first search over existing include edges. A back edge to a file already on the current
     * stack is one cycle; only the edge that closes it is reported, so a two-node cycle yields one
     * finding rather than one per participant.
     */
    private static List<Violation> cycles(RuleContext context, Map<Path, List<Edge>> edgesByFile,
                                          Set<Path> knownFiles) {
        List<Violation> violations = new ArrayList<>();
        Map<Path, Integer> state = new HashMap<>();
        List<Path> stack = new ArrayList<>();
        for (Path file : knownFiles) {
            if (state.getOrDefault(file, 0) == 0) {
                visit(context, file, edgesByFile, state, stack, violations);
            }
        }
        return violations;
    }

    private static void visit(RuleContext context, Path file, Map<Path, List<Edge>> edgesByFile,
                              Map<Path, Integer> state, List<Path> stack, List<Violation> violations) {
        state.put(file, 1);
        stack.add(file);
        for (Edge edge : edgesByFile.getOrDefault(file, List.of())) {
            Path target = edge.target();
            if (!Files.isRegularFile(target)) {
                continue;
            }
            int targetState = state.getOrDefault(target, 0);
            if (targetState == 1) {
                int start = stack.indexOf(target);
                List<String> cycle = new ArrayList<>();
                for (int i = start; i < stack.size(); i++) {
                    cycle.add(name(context, stack.get(i)));
                }
                cycle.add(name(context, target));
                violations.add(includeCycle(context, edge, cycle));
            } else if (targetState == 0) {
                visit(context, target, edgesByFile, state, stack, violations);
            }
        }
        stack.remove(stack.size() - 1);
        state.put(file, 2);
    }

    private static String name(RuleContext context, Path file) {
        return context.relativePath(file).substring(context.relativePath(file).lastIndexOf('/') + 1);
    }

    private record Edge(HtlIncludeRef reference, Path source, Path target) {
    }
}
