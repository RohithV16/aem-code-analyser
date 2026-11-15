package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AEM-SEC-002: open redirect / SSRF when request-controlled data reaches a redirect or outbound URL.
 */
public final class AemSec002Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_SEC_002.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (var classNode : context.buildResult().classNodes().values()) {
            var file = classNode.getFilePath();
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.contains("sendredirect(") && !lower.contains("sendredirect(\"")) {
                        violations.add(violation(context, file, i,
                                "OPEN_REDIRECT", "Redirect target is not a constant and may be user-controlled."));
                    } else if (lower.contains("new url(")
                            && (lower.contains("getparameter") || lower.contains("request."))) {
                        violations.add(violation(context, file, i,
                                "SSRF", "Outbound URL is built from request-controlled input."));
                    }
                }
            } catch (IOException ignored) {
                // unreadable file: diagnostics handled by the parser
            }
        }
        return violations;
    }

    private Violation violation(RuleContext context, java.nio.file.Path file, int index,
                                String subtype, String message) {
        return new Violation(
                RuleId.AEM_SEC_002.id(),
                RuleId.AEM_SEC_002.defaultLevel(),
                subtype,
                message,
                context.projectRoot().relativize(file.toAbsolutePath().normalize())
                        .toString().replace('\\', '/'),
                new TextRegion(index + 1, 1, index + 1, 2),
                null,
                "Validate the target against an allowlist and never pass request parameters directly"
                        + " into redirects or outbound HTTP clients.",
                List.of());
    }
}
