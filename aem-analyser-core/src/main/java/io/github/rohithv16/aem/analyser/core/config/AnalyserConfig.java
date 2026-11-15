package io.github.rohithv16.aem.analyser.core.config;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.util.LinkedHashMap;
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
        RuleOverride override = rules.get(ruleId);
        return override == null || !override.disabled();
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
        RuleOverride override = rules.get(violation.ruleId());
        if (override == null || override.severity() == null || override.severity() == violation.severity()) {
            return violation;
        }
        return new Violation(violation.ruleId(), override.severity(), violation.subtype(),
                violation.message(), violation.filePath(), violation.region(), violation.targetPid(),
                violation.remediation(), violation.relatedLocations());
    }
}
