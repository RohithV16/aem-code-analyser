package io.github.rohithv16.aem.analyser.core.rules.metadata;

import io.github.rohithv16.aem.analyser.core.model.report.Severity;

import java.util.List;

/**
 * Single source of truth for rule metadata consumed by every exporter and the CLI.
 *
 * @param id                canonical rule id (e.g. AEM-DEAD-001)
 * @param name              short machine-friendly name
 * @param defaultSeverity   default severity
 * @param shortDescription  one-line summary
 * @param fullDescription   longer explanation shown by {@code --explain}
 * @param helpUri           documentation link
 * @param tags              SARIF/Sonar tags
 * @param remediation       actionable fix
 * @param cwe               CWE id when applicable (e.g. CWE-79)
 * @param type              Sonar issue type
 * @param effortMinutes     estimated fix effort in minutes
 * @param securitySeverity  GitHub code-scanning security score in 0.1–10.0, or 0.0 when the rule is
 *                          not tagged {@code security}; consumed as SARIF
 *                          {@code properties.security-severity}
 * @param precision         how reliably a hit is a true positive ({@code high}, {@code medium},
 *                          {@code low}); consumed as SARIF {@code properties.precision}
 */
public record RuleDescriptor(
        String id,
        String name,
        Severity defaultSeverity,
        String shortDescription,
        String fullDescription,
        String helpUri,
        List<String> tags,
        String remediation,
        String cwe,
        RuleType type,
        int effortMinutes,
        double securitySeverity,
        String precision) {

    public RuleDescriptor {
        tags = List.copyOf(tags == null ? List.of() : tags);
        if (precision == null || precision.isBlank()) {
            precision = "medium";
        }
        if (securitySeverity < 0.0 || securitySeverity > 10.0 || (securitySeverity > 0.0 && securitySeverity < 0.1)) {
            throw new IllegalArgumentException(
                    "securitySeverity must be 0.0 (not security) or within 0.1–10.0: " + securitySeverity);
        }
    }

    /** True when the rule carries a GitHub security score and must be ranked as a security alert. */
    public boolean securityRanked() {
        return securitySeverity > 0.0;
    }
}
