package io.github.rohithv16.aem.analyser.core.rules.metadata;

import io.github.rohithv16.aem.analyser.core.baseline.Baseline;
import io.github.rohithv16.aem.analyser.core.baseline.BaselineEntry;
import io.github.rohithv16.aem.analyser.core.baseline.BaselineFilter;
import io.github.rohithv16.aem.analyser.core.baseline.InlineSuppressions;
import io.github.rohithv16.aem.analyser.core.config.AnalyserConfig;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AcceptanceOrigin;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.RuleStatus;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #55: ids never silently change meaning. A rename must resolve for configuration, inline
 * directives and baselines, and the committed lock must make removal a reviewed event.
 */
class RuleIdCompatibilityTest {

    @Test
    void retiredRuleCarriesStatusSinceAndReplacement() {
        RuleId retired = RuleId.fromId("AEM-QUAL-006");
        assertEquals(RuleStatus.RETIRED, retired.status());
        assertEquals("1.0.0", retired.since());
        assertEquals("AEM-QUAL-004", retired.replacedBy().orElseThrow());
        assertEquals(RuleStatus.ACTIVE, RuleId.fromId("AEM-QUAL-004").status());
        assertFalse(RuleId.fromId("AEM-QUAL-004").replacedBy().isPresent());
    }

    @Test
    void aliasesResolveToTheLiveReplacement() {
        assertTrue(RuleCatalog.isAlias("AEM-QUAL-006"));
        assertTrue(RuleCatalog.isRetired("AEM-QUAL-006"));
        assertEquals("AEM-QUAL-004", RuleCatalog.resolve("aem-qual-006"));
        assertEquals("AEM-QUAL-004", RuleCatalog.resolve("AEM-QUAL-004"));
        assertEquals("AEM-NOPE-999", RuleCatalog.resolve("AEM-NOPE-999"));
        assertEquals(RuleStatus.RETIRED, RuleCatalog.statusOf("AEM-QUAL-006"));
    }

    @Test
    void configDisablingARetiredIdDisablesItsReplacement() {
        AnalyserConfig config = new AnalyserConfig(1,
                Map.of("AEM-QUAL-006", new AnalyserConfig.RuleOverride(false, null)),
                new AnalyserConfig.Allowlists(java.util.Set.of(), java.util.Set.of()));

        assertFalse(config.isEnabled("AEM-QUAL-004"),
                "disabling AEM-QUAL-006 must disable the rule that produces its findings");
    }

    @Test
    void severityWrittenForARetiredIdDoesNotReRankTheReplacement() {
        AnalyserConfig config = new AnalyserConfig(1,
                Map.of("AEM-QUAL-006", new AnalyserConfig.RuleOverride(true, Severity.ERROR)),
                new AnalyserConfig.Allowlists(java.util.Set.of(), java.util.Set.of()));
        Violation warning = violation("AEM-QUAL-004", Severity.WARNING);

        AnalysisReport adjusted = config.apply(new AnalysisReport(Path.of("/tmp/p"), Map.of(),
                List.of(warning), 1));

        assertEquals(Severity.WARNING, adjusted.violations().get(0).severity(),
                "an entry naming a retired id still resolves, but cannot silently re-rank the live rule");
    }

    @Test
    void baselineWrittenUnderARetiredIdStillAcceptsTheReplacement(@TempDir Path root) {
        Violation finding = violation("AEM-QUAL-004", Severity.ERROR);
        // The entry's fingerprint is deliberately stale: it was written when the id was AEM-QUAL-006.
        Baseline baseline = new Baseline(Baseline.CURRENT_SCHEMA_VERSION, "test",
                List.of(new BaselineEntry("old-fingerprint", "AEM-QUAL-006",
                        finding.filePath(), finding.subtype())));

        AnalysisReport filtered = BaselineFilter.filter(new AnalysisReport(root, Map.of(),
                List.of(finding), 1), baseline);

        assertEquals(0, filtered.violations().size(),
                "a baseline naming a retired id must keep accepting the replacement's findings");
        assertEquals(AcceptanceOrigin.BASELINE, filtered.acceptedViolations().get(0).acceptance());
    }

    @Test
    void inlineDirectiveWrittenUnderARetiredIdSuppressesTheReplacement(@TempDir Path root) throws Exception {
        Path source = root.resolve("core/src/main/java/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, String.join("\n",
                "class A {",
                "    // aem-analyser-disable-next-line AEM-QUAL-006",
                "    void run() { }",
                "}"));
        Violation finding = violation("AEM-QUAL-004", Severity.ERROR);
        Violation other = violation("AEM-DEAD-001", Severity.WARNING);

        List<Violation> annotated = InlineSuppressions.annotate(root, List.of(finding, other));

        assertEquals(AcceptanceOrigin.INLINE, annotated.get(0).acceptance());
        assertEquals(AcceptanceOrigin.NONE, annotated.get(1).acceptance(),
                "an alias for one rule must not hide a different rule");
    }

    @Test
    void lockMatchesEveryDeclaredRuleId() throws Exception {
        String lock = Files.readString(repositoryFile(RuleIdsLock.FILE_NAME));

        List<String> problems = RuleIdsLock.diff(lock, RuleId.values());

        assertTrue(problems.isEmpty(),
                "rule-ids.lock drifted from RuleId (edit the enum and regenerate the lock):\n"
                        + String.join("\n", problems));
    }

    @Test
    void removingARuleIdFailsTheLockNamingIt() throws Exception {
        String lock = Files.readString(repositoryFile(RuleIdsLock.FILE_NAME));
        RuleId[] withoutDead003C = Arrays.stream(RuleId.values())
                .filter(rule -> rule != RuleId.AEM_DEAD_003C)
                .toArray(RuleId[]::new);

        List<String> problems = RuleIdsLock.diff(lock, withoutDead003C);

        assertFalse(problems.isEmpty(), "removing an id must fail the lock test");
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("AEM-DEAD-003C")),
                "the failure must name the removed id: " + problems);
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("two-release event")),
                "the failure must explain the deprecation contract: " + problems);
    }

    private static Violation violation(String ruleId, Severity severity) {
        return new Violation(ruleId, severity, "STATE", "message",
                "core/src/main/java/A.java", new TextRegion(3, 1, 3, 10), null, null, List.of(),
                AcceptanceOrigin.NONE);
    }

    private static Path repositoryFile(String name) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not find " + name + " above " + Path.of("").toAbsolutePath());
    }
}
