package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.SlingModelNode;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rule AEM-DUP-003 — Duplicate Sling Models.
 *
 * <p>Detects when multiple Sling Models bind to the exact same resourceType and adapter interface.
 * In Apache Sling Models, this causes non-deterministic adaptation when resolving via resourceType.
 */
public final class AemDup003Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_DUP_003.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        Map<String, List<SlingModelNode>> modelsByResourceType = new HashMap<>();

        for (SlingModelNode model : context.slingModels()) {
            model.getResourceType().ifPresent(rt -> {
                if (!rt.isBlank()) {
                    modelsByResourceType.computeIfAbsent(rt, k -> new ArrayList<>()).add(model);
                }
            });
        }

        for (Map.Entry<String, List<SlingModelNode>> entry : modelsByResourceType.entrySet()) {
            String rt = entry.getKey();
            List<SlingModelNode> models = entry.getValue();
            if (models.size() <= 1) {
                continue;
            }

            for (int i = 0; i < models.size(); i++) {
                for (int j = i + 1; j < models.size(); j++) {
                    SlingModelNode first = models.get(i);
                    SlingModelNode second = models.get(j);

                    Set<String> sharedAdapters = new HashSet<>(first.getAdapters());
                    sharedAdapters.retainAll(second.getAdapters());

                    // Conflict if they share an adapter or both have no adapters declared
                    if (!sharedAdapters.isEmpty() || (first.getAdapters().isEmpty() && second.getAdapters().isEmpty())) {
                        List<RelatedLocation> related = List.of(new RelatedLocation(
                                "Conflicting model: " + first.getFqn(),
                                relativePath(context, first.getFilePath()),
                                new TextRegion(first.getSourceLocation().line(), 1, first.getSourceLocation().line(), 1)
                        ));

                        violations.add(new Violation(
                                RuleId.AEM_DUP_003.id(),
                                RuleId.AEM_DUP_003.defaultLevel(),
                                "DUPLICATE_SLING_MODEL_RESOURCE_TYPE",
                                String.format("Sling Model '%s' conflicts with '%s': both claim resourceType = '%s'%s.",
                                        second.getFqn(), first.getFqn(), rt,
                                        sharedAdapters.isEmpty() ? "" : " with shared adapter " + sharedAdapters),
                                relativePath(context, second.getFilePath()),
                                new TextRegion(second.getSourceLocation().line(), 1, second.getSourceLocation().line(), 1),
                                rt,
                                "Assign distinct resourceTypes or differentiate adapter interfaces.",
                                related
                        ));
                    }
                }
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
