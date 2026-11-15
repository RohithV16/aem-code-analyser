package io.github.rohithv16.aem.analyser.core.fix;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoFixEngineTest {

    @Test
    void testFixDuplicateImportsAndBooleanLiterals(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("TestService.java");
        Files.writeString(javaFile,
                "package com.example;\n" +
                "\n" +
                "import java.util.List;\n" +
                "import java.util.List;\n" +
                "import java.util.Set;\n" +
                "\n" +
                "public class TestService {\n" +
                "    public void run(boolean flag) {\n" +
                "        if (flag == true) {\n" +
                "            byte[] b = \"hello\".getBytes(\"UTF-8\");\n" +
                "        }\n" +
                "    }\n" +
                "}\n"
        );

        List<Violation> violations = List.of(
                new Violation("java:S1128", Severity.WARNING, "S1128", "Duplicate import", "TestService.java",
                        TextRegion.forLine(4), null, "Fix", List.of()),
                new Violation("java:S1125", Severity.WARNING, "S1125", "Boolean literal", "TestService.java",
                        TextRegion.forLine(9), null, "Fix", List.of()),
                new Violation("java:S4719", Severity.WARNING, "S4719", "StandardCharsets", "TestService.java",
                        TextRegion.forLine(10), null, "Fix", List.of())
        );

        AutoFixEngine engine = new AutoFixEngine();

        // Dry run first
        AutoFixEngine.AutoFixSummary drySummary = engine.fix(tempDir, violations, true);
        assertEquals(1, drySummary.filesModified());
        assertTrue(drySummary.hasFixes());
        assertTrue(Files.readString(javaFile).contains("import java.util.List;\nimport java.util.List;\n"), "Dry run must not modify file on disk");

        // Real fix
        AutoFixEngine.AutoFixSummary summary = engine.fix(tempDir, violations, false);
        assertEquals(1, summary.filesModified());
        assertTrue(summary.fixesApplied() >= 3);

        String fixed = Files.readString(javaFile);
        assertFalse(fixed.contains("import java.util.List;\nimport java.util.List;"), "Duplicate import should be removed");
        assertTrue(fixed.contains("if (flag)"), "flag == true should be simplified to flag");
        assertTrue(fixed.contains("StandardCharsets.UTF_8"), "getBytes(\"UTF-8\") should be replaced with StandardCharsets.UTF_8");
        assertTrue(fixed.contains("import java.nio.charset.StandardCharsets;"), "StandardCharsets import should be added");
    }
}
