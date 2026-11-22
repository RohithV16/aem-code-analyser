package io.github.rohithv16.aem.analyser.core.config;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Committed project configuration ({@code .aem-analyser.yml}).
 *
 * <p>The optional {@code gate} block carries the quality gate in the same file the rules live in,
 * so {@code aem-analyser .} with no flags fails exactly the way the pipeline does. A flag on the
 * command line still wins for a one-off run: the gate is a set of defaults, not a policy the CLI
 * cannot override.</p>
 */
public record AnalyserConfig(
        Integer schemaVersion,
        Map<String, RuleOverride> rules,
        Allowlists allowlists,
        Gate gate) {

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

    /**
     * The committed quality gate. Every field is optional and independent: an unset field keeps the
     * command-line value (or the analyser default), so a project can pin only {@code failOn} and let
     * the rest stay a per-run choice.
     *
     * @param failOn         {@code NONE}, {@code ERROR}, {@code WARNING} or {@code INFO}
     * @param failOnWarning  fail when warnings are detected, like {@code --fail-on-warning}
     * @param maxWarnings    tolerated warning count before the gate fails
     * @param minCoverage    required test line coverage percentage
     * @param maxDuplication tolerated code duplication percentage
     * @param formats        report formats to produce when {@code -f/--format} is absent
     * @param output         output directory/file when {@code -o/--output} is absent
     */
    public record Gate(
            String failOn,
            Boolean failOnWarning,
            Integer maxWarnings,
            Double minCoverage,
            Double maxDuplication,
            List<String> formats,
            String output) {

        public Gate {
            formats = formats == null ? null : List.copyOf(formats);
        }

        /** True when no field is set, i.e. the block carries no instruction. */
        public boolean isEmpty() {
            return failOn == null && failOnWarning == null && maxWarnings == null
                    && minCoverage == null && maxDuplication == null
                    && (formats == null || formats.isEmpty()) && output == null;
        }
    }

    public AnalyserConfig {
        rules = Map.copyOf(rules == null ? Map.of() : rules);
        allowlists = allowlists == null ? new Allowlists(Set.of(), Set.of()) : allowlists;
    }

    /** Three-component shape kept for callers that have no gate block (tests, plugin, loaders). */
    public AnalyserConfig(Integer schemaVersion, Map<String, RuleOverride> rules, Allowlists allowlists) {
        this(schemaVersion, rules, allowlists, null);
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
