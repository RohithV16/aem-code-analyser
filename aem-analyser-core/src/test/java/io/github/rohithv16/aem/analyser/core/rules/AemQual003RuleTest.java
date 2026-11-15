package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParser;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual003Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AemQual003RuleTest {

    @TempDir
    Path root;

    private List<Violation> evaluate(String name, String source) throws Exception {
        Path file = root.resolve(name + ".java");
        Files.writeString(file, source);
        OSGiComponentNode component = new JavaSourceParser().parse(file).components().get(0);
        ReferenceGraph graph = new ReferenceGraph();
        ReferenceGraphBuilder.GraphBuildResult result = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Map.of(component.getComponentClassFqn(), component),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                List.of(), List.of(), List.of(), List.of(), null);
        return new AemQual003Rule().evaluate(new RuleContext(graph, result,
                Collections.emptySet(), null, Collections.emptySet(), root));
    }

    @Test
    void schedulerFieldIsDetected() throws Exception {
        List<Violation> violations = evaluate("SchedulerHolder", """
                package com.example;
                import org.apache.sling.commons.scheduler.Scheduler;
                import org.osgi.service.component.annotations.Component;
                import org.osgi.service.component.annotations.Reference;
                @Component(service = Runnable.class)
                public class SchedulerHolder implements Runnable {
                    @Reference
                    private Scheduler scheduler;
                    public void run() { }
                }
                """);

        assertTrue(violations.stream().anyMatch(v -> "SLING_SCHEDULER_USAGE".equals(v.subtype())),
                () -> "A Scheduler field indicates whiteboard scheduling: " + violations);
    }

    @Test
    void ocdStyleSchedulerPropertyIsDetected() throws Exception {
        List<Violation> violations = evaluate("OcdScheduler", """
                package com.example;
                import org.osgi.service.component.annotations.Component;
                @Component(service = Runnable.class, property = "scheduler_expression=0 0/30 * * * ?")
                public class OcdScheduler implements Runnable {
                    public void run() { }
                }
                """);

        assertTrue(violations.stream().anyMatch(v -> "SLING_SCHEDULER_USAGE".equals(v.subtype())),
                () -> "OCD-style scheduler_expression must be detected: " + violations);
    }
}
