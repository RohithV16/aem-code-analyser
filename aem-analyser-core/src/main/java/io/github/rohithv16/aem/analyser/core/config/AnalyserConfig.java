package io.github.rohithv16.aem.analyser.core.config;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleCatalog;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Committed project configuration ({@code .aem-analyser.yml}).
 */
public record AnalyserConfig(
        Integer schemaVersion,
        Map<String, RuleOverride> rules,
        Allowlists allowlists) {

    public record RuleOverride(Boolean enabled, Severity severity) {
        public boolean disabled() {
            return Boolean.FALSE.equals(enabled);
        }
    }

    public record Allowlists(Set<String> services, Set<String> configPaths) {
        public Allowlists {
            services = Set.copyOf(services == null ? Set.of() : services);
            configPaths = Set.copyOf(configPaths == null ? Set.of() : configPaths);
        }
    }

    public AnalyserConfig {
        rules = Map.copyOf(rules == null ? Map.of() : rules);
        allowlists = allowlists == null ? new Allowlists(Set.of(), Set.of()) : allowlists;
    }

    public static AnalyserConfig empty() {
        return new AnalyserConfig(1, Map.of(), new Allowlists(Set.of(), Set.of()));
    }

    public boolean isEnabled(String ruleId) {
        RuleOverride override = overrideFor(ruleId);
        return override == null || !override.disabled();
    }

    /**
     * The override that governs {@code ruleId}, resolving a legacy id to its replacement and vice
     * versa. A configuration that disables {@code AEM-QUAL-006} must still disable the live rule
     * that took over, otherwise the setting looks honoured while the findings keep failing the gate.
     *
     * <p>Only enablement is resolved across an alias: a severity written for a retired id is left on
     * that id (see {@link #withSeverityOverride}) so naming a dead rule cannot silently re-rank a
     * live one.
     */
    public RuleOverride overrideFor(String ruleId) {
        RuleOverride exact = rules.get(ruleId);
        if (exact != null || ruleId == null) {
            return exact;
        }
        String canonical = RuleCatalog.resolve(ruleId);
        for (Map.Entry<String, RuleOverride> entry : rules.entrySet()) {
            if (canonical.equals(RuleCatalog.resolve(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Applies rule enablement and severity overrides to an analysed report.
     */
    public AnalysisReport apply(AnalysisReport report) {
        if (rules.isEmpty()) {
            return report;
        }
        List<Violation> adjusted = report.violations().stream()
                .filter(violation -> isEnabled(violation.ruleId()))
                .map(this::withSeverityOverride)
                .toList();
        return report.withViolations(adjusted);
    }

    private Violation withSeverityOverride(Violation violation) {
        // Exact id only: an alias entry may control enablement, but applying its severity to the
        // replacement would make a retired id quietly change a live rule's gate weight.
        RuleOverride override = rules.get(violation.ruleId());
        if (override == null || override.severity() == null || override.severity() == violation.severity()) {
            return violation;
        }
        return new Violation(violation.ruleId(), override.severity(), violation.subtype(),
                violation.message(), violation.filePath(), violation.region(), violation.targetPid(),
                violation.remediation(), violation.relatedLocations());
    }
}
