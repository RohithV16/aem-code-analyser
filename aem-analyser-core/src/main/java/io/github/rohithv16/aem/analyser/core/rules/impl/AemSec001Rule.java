package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlUnsafeContextFinding;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule AEM-SEC-001: Unsafe HTL Context Bypass.
 * Flags occurrences of @ context='unsafe' in HTL files which disable XSS protections.
 */
public final class AemSec001Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_SEC_001.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        if (context.htlUnsafeContexts() == null || context.htlUnsafeContexts().isEmpty()) {
            return violations;
        }

        for (HtlUnsafeContextFinding finding : context.htlUnsafeContexts()) {
            boolean elementSink = finding.snippet() != null && finding.snippet().contains("data-sly-element");
            violations.add(new Violation(
                    RuleId.AEM_SEC_001.id(),
                    elementSink ? io.github.rohithv16.aem.analyser.core.model.report.Severity.WARNING
                            : RuleId.AEM_SEC_001.defaultLevel(),
                    "UNSAFE_HTL_CONTEXT_BYPASS",
                    "HTL expression uses '@ context=\\'unsafe\\'' which disables automatic XSS protection: " + finding.snippet(),
                    relativePath(context, finding.filePath()),
                    new TextRegion(finding.line(), finding.column(), finding.line(), finding.column() + 18),
                    null,
                    "Replace '@ context=\\'unsafe\\'' with an appropriate safe context such as 'html', 'attribute', 'uri', or sanitize output using AntiSamy / XSSAPI.",
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
