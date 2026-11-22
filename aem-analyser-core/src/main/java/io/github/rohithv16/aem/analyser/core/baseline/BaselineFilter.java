package io.github.rohithv16.aem.analyser.core.baseline;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.util.List;
import java.util.Map;

/**
 * Annotates findings already accepted in a baseline instead of deleting them.
 *
 * <p>Accepted findings leave {@link AnalysisReport#violations()} — the gate, the console summary and
 * therefore the exit code keep counting only actionable findings — and move to
 * {@link AnalysisReport#acceptedViolations()} carrying
 * {@link io.github.rohithv16.aem.analyser.core.model.report.AcceptanceOrigin#BASELINE}. SARIF then
 * exports them as suppressed results at their real level, so an uploaded report reads "36 exist, 36
 * accepted" instead of "0 problems".
 */
public final class BaselineFilter {

    private BaselineFilter() {
    }

    public static AnalysisReport filter(AnalysisReport report, Baseline baseline) {
        return filter(report, baseline, false);
    }

    /**
     * @param keepSuppressed legacy {@code --baseline-report} mode: additionally re-adds accepted
     *                       findings to {@link AnalysisReport#violations()} at INFO so renderers that
     *                       cannot express a suppression still list them. The gate-visible list then
     *                       contains them, exactly as before this change.
     */
    public static AnalysisReport filter(AnalysisReport report, Baseline baseline, boolean keepSuppressed) {
        if (baseline == null || baseline.entries().isEmpty()) {
            return report;
        }
        // A baseline stores one entry per accepted occurrence, so identical fingerprints are a
        // multiset: N accepted copies may suppress N occurrences, never an unlimited number. Using
        // contains() let a single accepted finding grandfather every future duplicate forever.
        Map<String, Integer> remaining = new java.util.HashMap<>();
        for (BaselineEntry entry : baseline.entries()) {
            remaining.merge(entry.fingerprint(), 1, Integer::sum);
        }
        // An id rename changes the fingerprint (it hashes the rule id), which would silently
        // re-open every accepted finding. Entries naming an alias are matched to the canonical rule
        // by location and subtype, once each, as a fallback after exact fingerprints.
        boolean[] aliasUsed = new boolean[baseline.entries().size()];
        for (int i = 0; i < baseline.entries().size(); i++) {
            BaselineEntry entry = baseline.entries().get(i);
            if (entry.ruleId() != null
                    && io.github.rohithv16.aem.analyser.core.rules.RuleCatalog.isAlias(entry.ruleId())) {
                aliasUsed[i] = false;
            }
        }

        List<Violation> filtered = new java.util.ArrayList<>();
        List<Violation> suppressed = new java.util.ArrayList<>();
        for (Violation violation : report.violations()) {
            String fingerprint = Fingerprint.of(violation);
            int allowed = remaining.getOrDefault(fingerprint, 0);
            if (allowed > 0) {
                remaining.put(fingerprint, allowed - 1);
                suppressed.add(violation);
            } else if (suppressedByAlias(violation, baseline.entries(), aliasUsed)) {
                suppressed.add(violation);
            } else {
                filtered.add(violation);
            }
        }
        List<Violation> accepted = new java.util.ArrayList<>(report.acceptedViolations());
        for (Violation violation : suppressed) {
            // Annotate, never rewrite: severity, subtype, message, region and fingerprint stay the
            // ones the rule produced, so the export still shows where and how bad the finding is.
            accepted.add(violation.withAcceptance(
                    io.github.rohithv16.aem.analyser.core.model.report.AcceptanceOrigin.BASELINE));
        }
        if (keepSuppressed) {
            for (Violation violation : suppressed) {
                filtered.add(new Violation(
                        violation.ruleId(),
                        io.github.rohithv16.aem.analyser.core.model.report.Severity.INFO,
                        "SUPPRESSED_BY_BASELINE",
                        "[baseline] " + violation.message(),
                        violation.filePath(),
                        violation.region(),
                        violation.targetPid(),
                        violation.remediation(),
                        violation.relatedLocations(),
                        io.github.rohithv16.aem.analyser.core.model.report.AcceptanceOrigin.BASELINE));
            }
        }
        return report.withViolations(filtered).withAcceptedViolations(accepted);
    }

    /**
     * Matches a finding to a baseline entry written under an old id. Only entries naming an alias
     * participate, and each entry can accept one occurrence; a baseline written under the canonical
     * id still uses fingerprints so a new finding cannot slide into an old waiver.
     */
    private static boolean suppressedByAlias(Violation violation, List<BaselineEntry> entries,
                                             boolean[] used) {
        String file = Fingerprint.normalizePath(violation.filePath());
        for (int i = 0; i < entries.size(); i++) {
            if (used[i]) {
                continue;
            }
            BaselineEntry entry = entries.get(i);
            if (entry.ruleId() == null
                    || !io.github.rohithv16.aem.analyser.core.rules.RuleCatalog.isAlias(entry.ruleId())) {
                continue;
            }
            String canonical = io.github.rohithv16.aem.analyser.core.rules.RuleCatalog.resolve(entry.ruleId());
            if (!canonical.equalsIgnoreCase(violation.ruleId())) {
                continue;
            }
            if (!file.equals(Fingerprint.normalizePath(entry.filePath()))) {
                continue;
            }
            if (!java.util.Objects.equals(
                    entry.subtype() == null ? null : entry.subtype().toUpperCase(java.util.Locale.ROOT),
                    violation.subtype())) {
                continue;
            }
            used[i] = true;
            return true;
        }
        return false;
    }

    public static int suppressedCount(AnalysisReport before, AnalysisReport after) {
        return Math.max(0, before.violations().size() - after.violations().size());
    }
}
