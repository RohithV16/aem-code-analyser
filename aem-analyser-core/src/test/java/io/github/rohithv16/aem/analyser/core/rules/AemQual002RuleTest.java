package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParser;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual002Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

class AemQual002RuleTest {

    @TempDir
    Path root;

    @Test
    void plainClassImplementingProviderTypeIsFlagged() throws Exception {
        Path file = root.resolve("BadProvider.java");
        Files.writeString(file, """
                package com.probe;
                class BadProvider implements org.apache.sling.api.resource.ResourceResolver {
                }
                """);

        JavaClassNode node = new JavaSourceParser().parse(file).javaClasses().get(0);
        ReferenceGraph graph = new ReferenceGraph();
        graph.addVertex(node);
        ReferenceGraphBuilder.GraphBuildResult result = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), Map.of(node.getFqn(), node),
                Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(), null);
        RuleContext context = new RuleContext(graph, result, Collections.emptySet(), null,
                Collections.emptySet(), root);

        List<Violation> violations = new AemQual002Rule().evaluate(context);

        assertTrue(violations.size() == 1, () -> "Expected one QUAL-002 finding: " + violations
                + " interfaces=" + node.getInterfaces() + " kind=" + node.getKind()
                + " super=" + node.getSuperClass());
    }

    private static void assertTrue(boolean condition, java.util.function.Supplier<String> message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
