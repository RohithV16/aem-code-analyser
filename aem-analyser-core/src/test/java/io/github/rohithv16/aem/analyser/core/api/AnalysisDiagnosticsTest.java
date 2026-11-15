package io.github.rohithv16.aem.analyser.core.api;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisDiagnosticsTest {

    @TempDir
    Path root;

    private void write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void recordsUnparseableJavaAndConfigFilesWithoutFailingAnalysis() throws Exception {
        write("pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules><module>core</module><module>ui.config</module></modules>
                </project>
                """);
        write("core/pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0.0</version></parent>
                  <artifactId>core</artifactId>
                  <packaging>jar</packaging>
                  <build><plugins><plugin>
                    <groupId>org.apache.felix</groupId><artifactId>maven-bundle-plugin</artifactId>
                  </plugin></plugins></build>
                </project>
                """);
        write("ui.config/pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0.0</version></parent>
                  <artifactId>ui.config</artifactId>
                  <packaging>content-package</packaging>
                  <build><plugins><plugin>
                    <groupId>org.apache.jackrabbit</groupId><artifactId>filevault-package-maven-plugin</artifactId>
                    <configuration><packageType>container</packageType></configuration>
                  </plugin></plugins></build>
                </project>
                """);

        write("core/src/main/java/com/example/Broken.java", """
                package com.example;
                public class Broken {
                    void missingBrace( {
                """);
        write("ui.config/src/main/content/jcr_root/apps/sample/osgiconfig/config/com.example.Broken.cfg.json",
                "{ \"enabled\": }\n");

        AnalysisReport report = new DefaultAemCodeAnalyser().analyze(AnalysisContext.of(root));

        assertTrue(report.diagnostics().stream().anyMatch(d -> d.kind() == DiagnosticKind.JAVA_PARSE_ERROR),
                () -> "Expected a Java parse diagnostic: " + report.diagnostics());
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.kind() == DiagnosticKind.CONFIG_PARSE_ERROR),
                () -> "Expected a config parse diagnostic: " + report.diagnostics());
        assertTrue(report.diagnostics().stream().allMatch(d -> !d.path().isBlank()));
    }

    @Test
    void skipsFilesAboveConfiguredSizeLimit() throws Exception {
        write("pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version>
                  <packaging>pom</packaging><modules><module>core</module></modules>
                </project>
                """);
        write("core/pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version></parent>
                  <artifactId>core</artifactId><packaging>jar</packaging>
                </project>
                """);
        write("core/src/main/java/com/example/Large.java", """
                package com.example;
                public class Large { void run() { } }
                """);

        AnalysisReport report = new DefaultAemCodeAnalyser().analyze(
                AnalysisContext.builder(root).maxFileSizeBytes(10).build());

        assertTrue(report.diagnostics().stream().anyMatch(d -> d.kind() == DiagnosticKind.FILE_TOO_LARGE),
                () -> "Expected a FILE_TOO_LARGE diagnostic: " + report.diagnostics());
    }
}
