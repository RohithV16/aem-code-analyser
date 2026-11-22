package io.github.rohithv16.aem.analyser.core.interop;

import io.github.rohithv16.aem.analyser.core.model.report.AcceptanceOrigin;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Accepts our findings where an imported foreign report reported the same location under a mapped
 * rule.
 *
 * <p>Accepted findings leave {@link AnalysisReport#violations()} — the gate and exit code keep
 * counting only actionable findings — and move to {@link AnalysisReport#acceptedViolations()} with
 * {@link AcceptanceOrigin#EXTERNAL}, so SARIF exports them as
 * {@code suppressions:[{kind:"external",status:"accepted",justification:"sonar java:S3649 @ ..."}]}.
 * That is the migration path: legacy debt stays visible with its provenance, only new findings fail.
 */
public final class ExternalSuppressions {

    private ExternalSuppressions() {
    }

    /**
     * @param foreign findings read from the other analyser's SARIF
     * @param map     explicit foreign-id to our-id mapping; empty means coverage only, never suppress
     */
    public static Result apply(AnalysisReport report, List<ForeignFinding> foreign, ImportMap map) {
        if (report == null) {
            throw new IllegalArgumentException("report is required");
        }
        List<ForeignFinding> imported = foreign == null ? List.of() : foreign;
        ImportMap effectiveMap = map == null ? ImportMap.empty() : map;

        boolean[] mapped = new boolean[imported.size()];
        boolean[] used = new boolean[imported.size()];
        int mappedCount = 0;
        for (int i = 0; i < imported.size(); i++) {
            mapped[i] = effectiveMap.targetFor(imported.get(i).ruleId()).isPresent();
            if (mapped[i]) {
                mappedCount++;
            }
        }

        Path root = report.projectRoot();
        List<Violation> actionable = new ArrayList<>();
        List<Violation> accepted = new ArrayList<>(report.acceptedViolations());
        int suppressedCount = 0;
        for (Violation violation : report.violations()) {
            ForeignFinding match = match(violation, imported, effectiveMap, mapped, used, root);
            if (match == null) {
                actionable.add(violation);
            } else {
                accepted.add(violation.withAcceptance(AcceptanceOrigin.EXTERNAL, match.justification()));
                suppressedCount++;
            }
        }
        AnalysisReport adjusted = report.withViolations(actionable).withAcceptedViolations(accepted);
        return new Result(adjusted, imported.size(), mappedCount, suppressedCount);
    }

    /**
     * Finds the first unused foreign result that (a) maps to this finding's rule and (b) overlaps its
     * location. Both conditions are required: location alone would let a wrong map hide a finding,
     * and rule alone would accept every occurrence in the project.
     */
    private static ForeignFinding match(Violation violation, List<ForeignFinding> imported,
                                        ImportMap map, boolean[] mapped, boolean[] used, Path root) {
        if (violation.region() == null) {
            return null;
        }
        String findingPath = SarifImporter.normalizeUri(violation.filePath(), root);
        int start = violation.region().startLine();
        int end = violation.region().endLine();
        for (int i = 0; i < imported.size(); i++) {
            if (!mapped[i] || used[i]) {
                continue;
            }
            ForeignFinding foreign = imported.get(i);
            String target = map.targetFor(foreign.ruleId()).orElse(null);
            if (target == null || !target.equalsIgnoreCase(violation.ruleId())) {
                continue;
            }
            if (!findingPath.equals(foreign.uri())) {
                continue;
            }
            if (!foreign.overlaps(start, end)) {
                continue;
            }
            used[i] = true;
            return foreign;
        }
        return null;
    }

    /**
     * @param report          report with accepted findings moved out of the gate-visible list
     * @param foreignCount    every foreign result read, including results with no usable location
     * @param mappedCount     foreign results whose rule id appears in the map
     * @param suppressedCount our findings accepted because of a mapped, overlapping foreign result
     */
    public record Result(AnalysisReport report, int foreignCount, int mappedCount, int suppressedCount) {
    }
}
