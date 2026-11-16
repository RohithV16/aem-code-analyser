package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.java.AdministrativeCall;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Rule AEM-QUAL-005 — Deprecated Administrative API Usage (Adobe Cloud Manager AMSCORE-553 / WTTech AEM-11).
 *
 * <p>Flags calls to {@code loginAdministrative()} or {@code getAdministrativeResourceResolver()}.
 * These administrative APIs are strictly disabled in AEM as a Cloud Service and will throw runtime exceptions.
 * Access must be performed using dedicated service users via {@code ResourceResolverFactory.getServiceResourceResolver()}.
 */
public final class AemQual005Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_QUAL_005.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (AdministrativeCall call : context.administrativeCalls()) {
            String relPath = relativePath(context, call.filePath());
            int line = call.location() != null ? call.location().line() : 1;
            int col = call.location() != null ? call.location().column() : 1;

            violations.add(new Violation(
                    RuleId.AEM_QUAL_005.id(),
                    RuleId.AEM_QUAL_005.defaultLevel(),
                    "ADMINISTRATIVE_ACCESS_USAGE",
                    String.format("Deprecated administrative API '%s()' invoked in '%s'. Administrative logins are disabled in AEM as a Cloud Service (AMSCORE-553).",
                            call.methodName(), call.enclosingClassFqn() != null ? call.enclosingClassFqn() : "unknown class"),
                    relPath,
                    new TextRegion(line, col, line, col + call.methodName().length()),
                    call.methodName(),
                    "Refactor to use a service-user-based resolver acquisition (for example ResourceResolverFactory.getServiceResourceResolver or getResourceResolver with a mapped subservice).",
                    List.of()
            ));
        }
        return violations;
    }

    private String relativePath(RuleContext context, Path path) {
        return context.relativePath(path);
    }
}
