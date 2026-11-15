package io.github.rohithv16.aem.analyser.core.sonar;

import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SonarJavaScannerTest {

    @Test
    void testSonarJavaScanner_detectsStandardSonarIssues(@TempDir Path tempDir) throws IOException {
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        Path javaFile = srcDir.resolve("SampleService.java");

        Files.writeString(javaFile,
                "package com.example;\n" +
                "\n" +
                "public class SampleService {\n" +
                "    public static final int bad_constant = 1;\n" +
                "\n" +
                "    public void doWork() throws Exception {\n" +
                "        System.out.println(\"Debugging message\");\n" +
                "        System.exit(0);\n" +
                "    }\n" +
                "\n" +
                "    public void Bad_Method_Name() {\n" +
                "    }\n" +
                "}\n"
        );

        SonarJavaScanner scanner = new SonarJavaScanner(tempDir);
        List<Violation> violations = scanner.scan();

        assertFalse(violations.isEmpty(), "Expected SonarJava violations to be detected");

        Set<String> ruleIds = violations.stream()
                .map(Violation::ruleId)
                .collect(Collectors.toSet());

        // S106: System.out usage
        assertTrue(ruleIds.contains("java:S106"), "Expected java:S106 (System.out usage) but got: " + ruleIds);
        // S1147: System.exit
        assertTrue(ruleIds.contains("java:S1147"), "Expected java:S1147 (System.exit) but got: " + ruleIds);
        // S115: Constant naming
        assertTrue(ruleIds.contains("java:S115"), "Expected java:S115 (Constant naming) but got: " + ruleIds);
        // S100: Method naming
        assertTrue(ruleIds.contains("java:S100"), "Expected java:S100 (Method naming) but got: " + ruleIds);
    }

    @Test
    void testSonarJavaScanner_withFilteredRules(@TempDir Path tempDir) throws IOException {
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        Path javaFile = srcDir.resolve("SampleService.java");

        Files.writeString(javaFile,
                "package com.example;\n" +
                "\n" +
                "public class SampleService {\n" +
                "    public static final int bad_constant = 1;\n" +
                "    public void doWork() {\n" +
                "        System.out.println(\"Hello\");\n" +
                "    }\n" +
                "}\n"
        );

        SonarJavaScanner scanner = new SonarJavaScanner(tempDir, SonarRuleRegistry.createChecksFor("S106"));
        List<Violation> violations = scanner.scan();

        Set<String> ruleIds = violations.stream()
                .map(Violation::ruleId)
                .collect(Collectors.toSet());

        assertTrue(ruleIds.contains("java:S106"), "Expected only java:S106");
        assertFalse(ruleIds.contains("java:S115"), "Did not expect java:S115 since it was filtered out");
    }
}
