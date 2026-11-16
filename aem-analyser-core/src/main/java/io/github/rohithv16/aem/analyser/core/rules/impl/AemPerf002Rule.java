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
 * Rule AEM-PERF-002: Unclosed JCR Session.
 * Detects JCR Session instances opened via repository login or adaptation that are not logged out or closed.
 */
public final class AemPerf002Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_PERF_002.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        if (context.resourceLeaks() == null) {
            return violations;
        }

        for (ResourceLeakFinding finding : context.resourceLeaks()) {
            if (finding.kind() != ResourceLeakFinding.LeakKind.JCR_SESSION) {
                continue;
            }
            TextRegion region = new TextRegion(finding.line(), finding.column(), finding.line(), finding.column() + 20);
            String varDisplay = finding.variableName() != null ? "variable '" + finding.variableName() + "'" : "instance";
            violations.add(new Violation(
                    RuleId.AEM_PERF_002.id(),
                    RuleId.AEM_PERF_002.defaultLevel(),
                    "UNCLOSED_JCR_SESSION",
                    "JCR Session " + varDisplay + " acquired via " + finding.callName()
                            + " is never logged out or closed, leading to Oak session leaks.",
                    relativePath(context, finding.filePath()),
                    region,
                    null,
                    "Always close the owning ResourceResolver or call session.logout() only when this"
                            + " session was obtained from repository.login* or loginService().",
                    List.of()
            ));
        }

        return violations;
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        return context.relativePath(path);
    }
}
