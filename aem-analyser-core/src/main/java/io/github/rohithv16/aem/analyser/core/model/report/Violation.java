package io.github.rohithv16.aem.analyser.core.model.report;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;

import java.util.List;
import java.util.Locale;

/**
 * A single cross-boundary violation.
 *
 * @param ruleId           canonical rule identifier (AEM-DEAD-XXX)
 * @param severity         severity level (may override the rule default)
 * @param subtype          violation subtype (e.g. ORPHANED_OCD_NAMED_CONFIG)
 * @param message          human-readable diagnostic message
 * @param filePath         reactor-root-relative, '/'-separated path
 * @param region           1-based text region of the offending code
 * @param targetPid        the OSGi PID concerned, if any
 * @param remediation      actionable remediation step
 * @param relatedLocations secondary locations (related Java classes etc.)
 */
public record Violation(
        String ruleId,
        Severity severity,
        String subtype,
        String message,
        String filePath,
        TextRegion region,
        String targetPid,
        String remediation,
        List<RelatedLocation> relatedLocations) {

    public Violation {
        if (region == null) {
            region = TextRegion.forWholeFile();
        }
        relatedLocations = List.copyOf(relatedLocations == null ? List.of() : relatedLocations);
        if (subtype != null) {
            subtype = subtype.toUpperCase(Locale.ROOT);
        }
    }

    public RuleId rule() {
        return RuleId.fromId(ruleId);
    }

    public java.util.Optional<String> targetPidOpt() {
        return java.util.Optional.ofNullable(targetPid);
    }

    public java.util.Optional<String> remediationOpt() {
        return java.util.Optional.ofNullable(remediation);
    }
}
