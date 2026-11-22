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
 * @param acceptance       why the finding was accepted (baseline entry, inline directive) or
 *                         {@link AcceptanceOrigin#NONE} when it still counts against the gate
 * @param acceptanceDetail free-form provenance for the waiver, e.g. the foreign rule and location
 *                         that accepted an imported finding; empty when the origin needs no detail
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
        List<RelatedLocation> relatedLocations,
        AcceptanceOrigin acceptance,
        String acceptanceDetail) {

    /**
     * Creates an actionable finding. Rule implementations and tests keep using this shape; acceptance
     * is annotated later by {@link io.github.rohithv16.aem.analyser.core.baseline.BaselineFilter} or
     * {@link io.github.rohithv16.aem.analyser.core.baseline.InlineSuppressions}, not by the rules.
     */
    public Violation(String ruleId,
                     Severity severity,
                     String subtype,
                     String message,
                     String filePath,
                     TextRegion region,
                     String targetPid,
                     String remediation,
                     List<RelatedLocation> relatedLocations) {
        this(ruleId, severity, subtype, message, filePath, region, targetPid, remediation,
                relatedLocations, AcceptanceOrigin.NONE, "");
    }

    /** Creates a finding with an acceptance origin but no extra provenance. */
    public Violation(String ruleId,
                     Severity severity,
                     String subtype,
                     String message,
                     String filePath,
                     TextRegion region,
                     String targetPid,
                     String remediation,
                     List<RelatedLocation> relatedLocations,
                     AcceptanceOrigin acceptance) {
        this(ruleId, severity, subtype, message, filePath, region, targetPid, remediation,
                relatedLocations, acceptance, "");
    }

    public Violation {
        if (region == null) {
            region = TextRegion.forWholeFile();
        }
        relatedLocations = List.copyOf(relatedLocations == null ? List.of() : relatedLocations);
        if (subtype != null) {
            subtype = subtype.toUpperCase(Locale.ROOT);
        }
        if (acceptance == null) {
            acceptance = AcceptanceOrigin.NONE;
        }
        acceptanceDetail = acceptanceDetail == null ? "" : acceptanceDetail;
    }

    /** Marks this finding as accepted without changing its severity, message or location. */
    public Violation withAcceptance(AcceptanceOrigin origin) {
        return withAcceptance(origin, acceptanceDetail);
    }

    /** Marks this finding as accepted, recording why (e.g. the foreign rule and location). */
    public Violation withAcceptance(AcceptanceOrigin origin, String detail) {
        return new Violation(ruleId, severity, subtype, message, filePath, region, targetPid,
                remediation, relatedLocations, origin, detail);
    }

    public boolean isAccepted() {
        return acceptance.isAccepted();
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
