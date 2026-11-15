package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.java.CodeBlockSnapshot;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule AEM-DUP-010 — Duplicate Java Code Blocks / Method Clones.
 *
 * <p>Detects identical or near-identical method implementations across repository classes.
 * Flags copy-pasted workflow steps, asset processors, or business logic that should be
 * centralized into reusable OSGi services or utilities.
 */
public final class AemDup010Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_DUP_010.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        Map<String, List<CodeBlockSnapshot>> blocksByHash = new HashMap<>();

        for (CodeBlockSnapshot block : context.codeBlocks()) {
            blocksByHash.computeIfAbsent(block.normalizedHash(), k -> new ArrayList<>()).add(block);
        }

        for (List<CodeBlockSnapshot> duplicates : blocksByHash.values()) {
            if (duplicates.size() <= 1) {
                continue;
            }

            CodeBlockSnapshot first = duplicates.get(0);
            for (int i = 1; i < duplicates.size(); i++) {
                CodeBlockSnapshot other = duplicates.get(i);

                // Avoid duplicate reporting if in the exact same class and method
                if (first.classFqn().equals(other.classFqn()) && first.methodName().equals(other.methodName())) {
                    continue;
                }

                List<RelatedLocation> related = List.of(new RelatedLocation(
                        String.format("Original block in %s::%s (%d lines)", first.classFqn(), first.methodName(), first.lineCount()),
                        relativePath(context, first.filePath()),
                        new TextRegion(first.startLine(), 1, first.endLine(), 1)
                ));

                String preview = other.preview().isEmpty() ? "" : " Preview: " + other.preview();
                if (preview.length() > 80) {
                    preview = preview.substring(0, 80) + "...";
                }

                violations.add(new Violation(
                        RuleId.AEM_DUP_010.id(),
                        RuleId.AEM_DUP_010.defaultLevel(),
                        "DUPLICATE_CODE_BLOCK",
                        String.format("Duplicate code block (%d lines, %d statements) in %s::%s matches %s::%s.%s",
                                other.lineCount(), other.statementCount(),
                                other.classFqn(), other.methodName(),
                                first.classFqn(), first.methodName(),
                                preview),
                        relativePath(context, other.filePath()),
                        new TextRegion(other.startLine(), 1, other.endLine(), 1),
                        other.classFqn() + "#" + other.methodName(),
                        "Refactor duplicated logic into a shared OSGi service, Sling Model, or utility class.",
                        related
                ));
            }
        }

        return violations;
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
}
