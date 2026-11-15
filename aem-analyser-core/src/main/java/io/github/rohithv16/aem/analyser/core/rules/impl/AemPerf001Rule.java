package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.perf.ResourceLeakFinding;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule AEM-PERF-001: Unclosed ResourceResolver.
 * Detects ResourceResolver instances acquired via ResourceResolverFactory that are not
 * closed in a try-with-resources statement or finally block, causing memory and session leaks.
 */
public final class AemPerf001Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_PERF_001.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        if (context.resourceLeaks() == null) {
            return violations;
        }

        for (ResourceLeakFinding finding : context.resourceLeaks()) {
            if (finding.kind() != ResourceLeakFinding.LeakKind.RESOURCE_RESOLVER) {
                continue;
            }
            TextRegion region = new TextRegion(finding.line(), finding.column(), finding.line(), finding.column() + 25);
            String varDisplay = finding.variableName() != null ? "variable '" + finding.variableName() + "'" : "instance";
            violations.add(new Violation(
                    RuleId.AEM_PERF_001.id(),
                    RuleId.AEM_PERF_001.defaultLevel(),
                    "UNCLOSED_RESOURCE_RESOLVER",
                    "ResourceResolver " + varDisplay + " opened via " + finding.callName()
                            + "() is never closed in try-with-resources or finally block.",
                    relativePath(context, finding.filePath()),
                    region,
                    null,
                    "Wrap in a try-with-resources statement: try (ResourceResolver rr = factory." + finding.callName() + "(...)) { ... }",
                    List.of()
            ));
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
