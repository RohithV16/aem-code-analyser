package io.github.rohithv16.aem.analyser.maven;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #44: the reactor <em>finding attribution</em> rules.
 *
 * <p>Attribution is the whole risk of per-module reports: a wrong prefix either hides a finding in
 * the wrong module's report or loses it entirely. These tests pin the three cases the issue names —
 * module A, module B, unmatched — plus the nesting and root-aggregator cases a real AEM archetype
 * exercises (the root POM is not a code module; {@code all} contains {@code src}).
 */
class ModuleAttributionTest {

    private static final Path ROOT = Path.of("/work/portfolio");

    @Test
    void attributesToTheOwningModuleByPrefix() {
        ModuleAttributor attributor = ModuleAttributor.of(List.of(
                new ModuleAttributor.Module("core", "core"),
                new ModuleAttributor.Module("ui.apps", "ui.apps")));

        assertEquals("core", attributor.moduleFor("core/src/main/java/A.java"));
        assertEquals("ui.apps", attributor.moduleFor("ui.apps/src/main/content/jcr_root/.content.xml"));
    }

    @Test
    void unmatchedPathLandsInTheReactorBucketInsteadOfVanishing() {
        ModuleAttributor attributor = ModuleAttributor.of(List.of(
                new ModuleAttributor.Module("core", "core"),
                new ModuleAttributor.Module("ui.apps", "ui.apps")));

        assertEquals(ModuleAttributor.REACTOR_BUCKET, attributor.moduleFor("zzprobe/no-module/file.java"));
    }

    @Test
    void theReactorRootPomNeverClaimsAnotherModulesFindings() {
        ModuleAttributor attributor = ModuleAttributor.of(List.of(
                new ModuleAttributor.Module("rohithaem-portfolio", ""),
                new ModuleAttributor.Module("core", "core")));

        assertEquals("core", attributor.moduleFor("core/src/main/java/A.java"));
        // Before #44 the root aggregation was the only report; a per-module run must not give the
        // aggregator POM every unowned file as if it were a code module.
        assertEquals(ModuleAttributor.REACTOR_BUCKET, attributor.moduleFor("zzprobe/no-module/file.java"));
        assertTrue(attributor.isReactor());
    }

    @Test
    void theLongestPrefixWinsForNestedModules() {
        ModuleAttributor attributor = ModuleAttributor.of(List.of(
                new ModuleAttributor.Module("all", "all"),
                new ModuleAttributor.Module("all-inner", "all/src")));

        assertEquals("all-inner", attributor.moduleFor("all/src/main/java/A.java"));
        assertEquals("all", attributor.moduleFor("all/pom.xml"));
        assertEquals("all", attributor.moduleFor("all/src2/main/java/A.java"));
    }

    @Test
    void aPathThatOnlySharesAFilenamePrefixIsNotClaimed() {
        ModuleAttributor attributor = ModuleAttributor.of(List.of(
                new ModuleAttributor.Module("core", "core")));

        assertEquals(ModuleAttributor.REACTOR_BUCKET, attributor.moduleFor("core-tests/src/main/java/A.java"));
    }

    @Test
    void aSingleModuleBuildOwnsItsWholeTree() {
        ModuleAttributor attributor = ModuleAttributor.of(List.of(
                new ModuleAttributor.Module("single", "")));

        assertEquals("single", attributor.moduleFor("src/main/java/A.java"));
        assertFalse(attributor.isReactor());
    }

    @Test
    void relativePathOfIsRootRelativeAndRejectsOutsideTrees() {
        assertEquals("core", ModuleAttributor.relativePathOf(ROOT, ROOT.resolve("core")));
        assertEquals("", ModuleAttributor.relativePathOf(ROOT, ROOT));
        assertEquals("ui/apps", ModuleAttributor.relativePathOf(ROOT, ROOT.resolve("ui/apps")));
        assertNull(ModuleAttributor.relativePathOf(ROOT.resolve("core"), ROOT.resolve("ui.apps")),
                "a module outside the analysed tree cannot own its files");
    }

    @Test
    void windowsSeparatorsAndDotPrefixesNormaliseToTheSameModule() {
        ModuleAttributor attributor = ModuleAttributor.of(List.of(
                new ModuleAttributor.Module("core", "./core/")));

        assertEquals("core", attributor.moduleFor("core\\src\\main\\java\\A.java"));
        assertEquals("core", attributor.moduleFor("./core/src/main/java/A.java"));
    }

    @Test
    void tableBucketsOneRowPerModuleAndPutsReactorLast() {
        ModuleAttributor attributor = ModuleAttributor.of(List.of(
                new ModuleAttributor.Module("core", "core"),
                new ModuleAttributor.Module("ui.apps", "ui.apps")));
        Map<String, List<Violation>> buckets = PerModuleSummary.bucket(attributor, List.of(
                violation("core/src/main/java/A.java", Severity.ERROR),
                violation("core/src/main/java/B.java", Severity.WARNING),
                violation("ui.apps/src/main/content/.content.xml", Severity.WARNING),
                violation("zzprobe/no-module/file.java", Severity.INFO)));

        List<PerModuleSummary.Row> rows = PerModuleSummary.rows(buckets);

        assertEquals(List.of("core", "ui.apps", ModuleAttributor.REACTOR_BUCKET),
                rows.stream().map(PerModuleSummary.Row::module).toList());
        assertEquals(1, rows.get(0).errors());
        assertEquals(1, rows.get(0).warnings());
        assertEquals(2, rows.get(0).total());
        assertEquals(1, rows.get(2).infos());
    }

    @Test
    void tableRendersHeaderAlignedRowsAndATotal() {
        List<String> lines = PerModuleSummary.render(List.of(
                new PerModuleSummary.Row("core", 2, 22, 2),
                new PerModuleSummary.Row(ModuleAttributor.REACTOR_BUCKET, 0, 0, 1)));

        assertEquals(5, lines.size(), String.join("\n", lines));
        assertTrue(lines.get(0).contains("2 module(s), 27 finding(s)"), lines.get(0));
        assertTrue(lines.get(1).contains("MODULE"), lines.get(1));
        assertTrue(lines.get(1).contains("ERRORS"), lines.get(1));
        assertTrue(lines.get(1).contains("WARNINGS"), lines.get(1));
        assertTrue(lines.get(2).trim().startsWith("core"), lines.get(2));
        assertTrue(lines.get(2).contains("26"), lines.get(2));
        assertTrue(lines.get(3).trim().startsWith(ModuleAttributor.REACTOR_BUCKET), lines.get(3));
        assertTrue(lines.get(4).trim().startsWith("TOTAL"), lines.get(4));
        assertTrue(lines.get(4).contains("27"), lines.get(4));
        // Every line has the same width: the columns line up under the header.
        assertEquals(lines.get(1).length(), lines.get(2).length(), String.join("\n", lines));
        assertEquals(lines.get(1).length(), lines.get(4).length(), String.join("\n", lines));
    }

    @Test
    void anEmptyRunRendersAHelpfulLineInsteadOfAnEmptyTable() {
        List<String> lines = PerModuleSummary.render(List.of());

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("no findings"), lines.get(0));
    }

    private static Violation violation(String path, Severity severity) {
        return new Violation("AEM-DEAD-001", severity, "SUBTYPE", "message", path,
                TextRegion.forWholeFile(), null, null, List.of());
    }
}
