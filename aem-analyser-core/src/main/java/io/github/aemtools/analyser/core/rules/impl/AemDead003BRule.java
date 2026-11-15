package io.github.aemtools.analyser.core.rules.impl;

import io.github.aemtools.analyser.core.model.graph.EdgeType;
import io.github.aemtools.analyser.core.model.graph.TextRegion;
import io.github.aemtools.analyser.core.model.graph.node.SlingModelNode;
import io.github.aemtools.analyser.core.model.report.RuleId;
import io.github.aemtools.analyser.core.model.report.Violation;
import io.github.aemtools.analyser.core.rules.RuleContext;
import io.github.aemtools.analyser.core.rules.RuleEvaluator;

import java.util.List;

/**
 * FEAT-RULE-07: Rule AEM-DEAD-003B — Unused Sling Models (Phase 1 boundary).
 * Sling Models with 0 Java adaptations but a resourceType binding are retained as active
 * candidates (HTL cross-referencing arrives in Phase 2); models without a resourceType
 * and zero adaptations are reported at INFO severity.
 */
public final class AemDead003BRule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_DEAD_003B.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new java.util.ArrayList<>();
        for (SlingModelNode model : context.buildResult().modelNodes().values()) {
            if (context.graph().inDegree(model, EdgeType.ADAPTS_TO) > 0) {
                continue;
            }
            if (model.getResourceType().isPresent()) {
                continue; // retained as active candidate until Phase 2 HTL analysis
            }
            var location = model.getSourceLocation();
            TextRegion region = location == null ? TextRegion.forWholeFile()
                    : new TextRegion(location.line(), location.column(),
                    location.line(), location.column() + 12);
            violations.add(new Violation(
                    RuleId.AEM_DEAD_003B.id(),
                    RuleId.AEM_DEAD_003B.defaultLevel(),
                    "UNUSED_SLING_MODEL",
                    "Sling Model '" + model.getFqn()
                            + "' has 0 Java adaptations and no resourceType binding"
                            + " (Pending Phase 2 HTL cross-referencing).",
                    relativePath(context, model.getFilePath()),
                    region,
                    null,
                    "Confirm usage in HTL (data-sly-use) before deleting; if truly unused,"
                            + " remove the model class.",
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
