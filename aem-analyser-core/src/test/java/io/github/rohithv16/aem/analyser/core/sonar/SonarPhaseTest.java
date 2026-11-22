package io.github.rohithv16.aem.analyser.core.sonar;

import io.github.rohithv16.aem.analyser.core.api.AnalysisContext;
import io.github.rohithv16.aem.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #60: the Sonar phase must consume discovery's file set and honour the run's filters, so
 * {@code --changed-only} / {@code --max-file-size} mean the same thing in every phase.
 */
class SonarPhaseTest {

    @TempDir
    Path projectRoot;

    private Path write(String relativePath, String content) throws Exception {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    /** Minimal two-module reactor whose two classes both trip SonarJava's S106 (System.out). */
    private void writeProject() throws Exception {
        write("pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>sonar-fixture</artifactId>
                  <version>1.0.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                  </modules>
                </project>
                """);
        write("core/pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>sonar-fixture</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                  </parent>
                  <artifactId>sonar-fixture-core</artifactId>
                  <packaging>jar</packaging>
                </project>
                """);
        write("core/src/main/java/com/example/changed/Changed.java", """
                package com.example.changed;
                public class Changed {
                    public void run() {
                        System.out.println("changed");
                    }
                }
                """);
        write("core/src/main/java/com/example/unchanged/Unchanged.java", """
                package com.example.unchanged;
                public class Unchanged {
                    public void run() {
                        System.out.println("unchanged");
                    }
                }
                """);
    }

    private static List<Violation> sonarFindings(AnalysisReport report) {
        return report.violations().stream()
                .filter(v -> v.ruleId().startsWith("java:"))
                .toList();
    }

    @Test
    void fullRunStillReportsEverySonarFinding() throws Exception {
        writeProject();

        AnalysisReport report = new DefaultAemCodeAnalyser().analyze(AnalysisContext.of(projectRoot));

        List<Violation> sonar = sonarFindings(report);
        assertTrue(sonar.stream().anyMatch(v -> v.filePath().contains("Changed.java")),
                () -> "Sonar must see the changed class: " + sonar);
        assertTrue(sonar.stream().anyMatch(v -> v.filePath().contains("Unchanged.java")),
                () -> "Sonar must see the unchanged class on a full run: " + sonar);
    }

    @Test
    void changedOnlyNarrowsSonarToDiscoveryFileSet() throws Exception {
        writeProject();

        AnalysisContext context = AnalysisContext.builder(projectRoot)
                .changedFiles(Set.of("core/src/main/java/com/example/changed/Changed.java"))
                .build();
        AnalysisReport report = new DefaultAemCodeAnalyser().analyze(context);

        List<Violation> sonar = sonarFindings(report);
        assertTrue(sonar.stream().anyMatch(v -> v.filePath().contains("Changed.java")),
                () -> "The changed class must still be analysed: " + sonar);
        assertFalse(sonar.stream().anyMatch(v -> v.filePath().contains("Unchanged.java")),
                () -> "Sonar must not widen --changed-only to unchanged files: " + sonar);
    }

    @Test
    void maxFileSizeAppliesToSonarToo() throws Exception {
        writeProject();

        AnalysisReport report = new DefaultAemCodeAnalyser().analyze(
                AnalysisContext.builder(projectRoot).maxFileSizeBytes(1).build());

        assertTrue(sonarFindings(report).isEmpty(),
                () -> "No file may reach Sonar above the size cap: " + sonarFindings(report));
    }

    @Test
    void sonarPhaseIsReportedOnlyWhenItRuns() throws Exception {
        writeProject();

        AnalysisReport withSonar = new DefaultAemCodeAnalyser().analyze(AnalysisContext.of(projectRoot));
        assertTrue(withSonar.phaseMillis().containsKey("sonar"),
                () -> "Phases: " + withSonar.phaseMillis());
        assertTrue(withSonar.phaseMillis().containsKey("discovery"));
        assertTrue(withSonar.phaseMillis().containsKey("parse"));

        AnalysisReport withoutSonar = new DefaultAemCodeAnalyser().analyze(
                AnalysisContext.builder(projectRoot).enableSonar(false).build());
        assertFalse(withoutSonar.phaseMillis().containsKey("sonar"),
                () -> "Phases: " + withoutSonar.phaseMillis());
    }
}
