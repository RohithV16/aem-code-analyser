package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PmdCpdDuplicationEngineTest {

    @Test
    void testDetectsDuplicationAcrossClasses(@TempDir Path tempDir) throws IOException {
        Path file1 = tempDir.resolve("ServiceA.java");
        Path file2 = tempDir.resolve("ServiceB.java");

        String methodBody = """
                package com.example;
                public class ServiceA {
                    public void handleWorkflow(String payload) {
                        if (payload == null || payload.isBlank()) {
                            System.out.println("Nothing to process here");
                            return;
                        }
                        for (int i = 0; i < payload.length(); i++) {
                            char c = payload.charAt(i);
                            System.out.println("Item: " + c);
                        }
                    }
                }
                """;
        Files.writeString(file1, methodBody);
        Files.writeString(file2, methodBody.replace("ServiceA", "ServiceB"));

        JavaClassNode nodeA = new JavaClassNode("com.example.ServiceA", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), null, Collections.emptySet(), file1, new SourceLocation(file1, 2, 1));
        JavaClassNode nodeB = new JavaClassNode("com.example.ServiceB", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), null, Collections.emptySet(), file2, new SourceLocation(file2, 2, 1));

        JavaSourceParseResult resA = new JavaSourceParseResult(file1, "com.example", List.of(nodeA),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Set.of(), List.of(), 15);
        JavaSourceParseResult resB = new JavaSourceParseResult(file2, "com.example", List.of(nodeB),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Set.of(), List.of(), 15);

        PmdCpdDuplicationEngine engine = new PmdCpdDuplicationEngine(20);
        PmdCpdDuplicationEngine.Result result = engine.analyze(tempDir, List.of(resA, resB));

        assertNotNull(result);
        assertNotNull(result.metrics());
        assertTrue(result.metrics().duplicatedLines() > 0, "Should detect duplicated lines");
        assertTrue(result.metrics().duplicationPercent() > 0.0, "Duplication % should be > 0");
        assertFalse(result.metrics().duplicateClasses().isEmpty(), "Classes list should not be empty");
        assertFalse(result.violations().isEmpty(), "Should produce AEM-DUP-010 violations");
        assertEquals("AEM-DUP-010", result.violations().get(0).ruleId());
    }

    @Test
    void testNoDuplicationReturnsZeroMetrics(@TempDir Path tempDir) throws IOException {
        Path file1 = tempDir.resolve("ServiceA.java");
        Path file2 = tempDir.resolve("ServiceB.java");

        Files.writeString(file1, "package com.example; public class ServiceA { public void a() { int x = 1; } }");
        Files.writeString(file2, "package com.example; public class ServiceB { public void b() { String s = \"hello\"; } }");

        JavaClassNode nodeA = new JavaClassNode("com.example.ServiceA", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), null, Collections.emptySet(), file1, new SourceLocation(file1, 1, 1));
        JavaClassNode nodeB = new JavaClassNode("com.example.ServiceB", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), null, Collections.emptySet(), file2, new SourceLocation(file2, 1, 1));

        JavaSourceParseResult resA = new JavaSourceParseResult(file1, "com.example", List.of(nodeA),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Set.of(), List.of(), 1);
        JavaSourceParseResult resB = new JavaSourceParseResult(file2, "com.example", List.of(nodeB),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Set.of(), List.of(), 1);

        PmdCpdDuplicationEngine engine = new PmdCpdDuplicationEngine(50);
        PmdCpdDuplicationEngine.Result result = engine.analyze(tempDir, List.of(resA, resB));

        assertEquals(0, result.metrics().duplicatedLines());
        assertEquals(0.0, result.metrics().duplicationPercent());
        assertTrue(result.metrics().duplicateClasses().isEmpty());
        assertTrue(result.violations().isEmpty());
    }
}
