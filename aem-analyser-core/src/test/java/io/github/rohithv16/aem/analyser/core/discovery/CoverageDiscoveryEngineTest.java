package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleClassification;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor;
import io.github.rohithv16.aem.analyser.core.model.report.CoverageReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CoverageDiscoveryEngineTest {

    @Test
    void testNoCoverageFilesReturnsNotFound(@TempDir Path tempDir) {
        CoverageDiscoveryEngine engine = new CoverageDiscoveryEngine();
        CoverageReport report = engine.discover(tempDir, List.of());
        assertNotNull(report);
        assertFalse(report.present());
        assertEquals(0, report.totalLines());
        assertEquals(0.0, report.lineCoveragePercent());
    }

    @Test
    void testDiscoversAndParsesJacocoCsv(@TempDir Path tempDir) throws IOException {
        Path jacocoDir = tempDir.resolve("target/site/jacoco");
        Files.createDirectories(jacocoDir);
        Path csvFile = jacocoDir.resolve("jacoco.csv");

        String csvContent = """
                GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
                core,com.example.core,ExampleService,10,90,2,8,5,45,2,8,1,9
                core,com.example.core,HelperClass,5,45,1,3,5,15,1,3,1,4
                """;
        Files.writeString(csvFile, csvContent);

        CoverageDiscoveryEngine engine = new CoverageDiscoveryEngine();
        CoverageReport report = engine.discover(tempDir, List.of());

        assertTrue(report.present());
        assertEquals(60, report.coveredLines());
        assertEquals(70, report.totalLines()); // 60 covered + 10 missed
        assertEquals((60.0 * 100.0) / 70.0, report.lineCoveragePercent(), 0.01);
        assertEquals(11, report.coveredBranches());
        assertEquals(14, report.totalBranches()); // 11 covered + 3 missed
        assertEquals((11.0 * 100.0) / 14.0, report.branchCoveragePercent(), 0.01);
        assertNotNull(report.sourcePath());
    }

    @Test
    void testDiscoversCoverageInSubmodules(@TempDir Path tempDir) throws IOException {
        Path subModule = tempDir.resolve("core");
        Path jacocoDir = subModule.resolve("target/site/jacoco");
        Files.createDirectories(jacocoDir);
        Path csvFile = jacocoDir.resolve("jacoco.csv");

        String csvContent = """
                GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
                core,com.example.core,ExampleService,0,100,0,10,0,50,0,5,0,5
                """;
        Files.writeString(csvFile, csvContent);

        ModuleDescriptor descriptor = new ModuleDescriptor(
                "core",
                "com.example",
                "jar",
                subModule,
                subModule.resolve("pom.xml"),
                ModuleClassification.BUNDLE_MODULE,
                "parent",
                false,
                Set.of(),
                Set.of()
        );

        CoverageDiscoveryEngine engine = new CoverageDiscoveryEngine();
        CoverageReport report = engine.discover(tempDir, List.of(descriptor));

        assertTrue(report.present());
        assertEquals(50, report.coveredLines());
        assertEquals(50, report.totalLines());
        assertEquals(100.0, report.lineCoveragePercent());
    }
}
