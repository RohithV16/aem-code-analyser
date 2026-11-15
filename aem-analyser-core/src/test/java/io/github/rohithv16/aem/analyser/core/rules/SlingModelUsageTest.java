package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.node.SlingModelNode;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParser;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead003BRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SlingModelUsageTest {

    @TempDir
    Path root;

    @Test
    void modelsConsumedThroughInjectionAreNotReportedUnused() throws Exception {
        SlingModelNode address = parse("Address", """
                package com.example.models;
                import org.apache.sling.api.resource.Resource;
                import org.apache.sling.models.annotations.Model;
                @Model(adaptables = Resource.class)
                public class Address {
                    public String getLine1() { return ""; }
                }
                """);
        SlingModelNode person = parse("Person", """
                package com.example.models;
                import org.apache.sling.api.resource.Resource;
                import org.apache.sling.models.annotations.Model;
                import org.apache.sling.models.annotations.injectorspecific.ChildResource;
                @Model(adaptables = Resource.class)
                public class Person {
                    @ChildResource
                    private Address address;
                }
                """);
        SlingModelNode listHolder = parse("ListHolder", """
                package com.example.models;
                import java.util.List;
                import org.apache.sling.api.resource.Resource;
                import org.apache.sling.models.annotations.Model;
                import org.apache.sling.models.annotations.injectorspecific.ChildResource;
                @Model(adaptables = Resource.class)
                public class ListHolder {
                    @ChildResource
                    private List<Address> addresses;
                }
                """);

        Map<String, SlingModelNode> models = new LinkedHashMap<>();
        models.put(address.getFqn(), address);
        models.put(person.getFqn(), person);
        models.put(listHolder.getFqn(), listHolder);

        ReferenceGraph graph = new ReferenceGraph();
        models.values().forEach(graph::addVertex);
        ReferenceGraphBuilder.GraphBuildResult result = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), models,
                List.of(), List.of(), List.of(), List.of(), null);
        RuleContext context = new RuleContext(graph, result, Collections.emptySet(), null,
                Collections.emptySet(), root);

        List<Violation> violations = new AemDead003BRule().evaluate(context);

        assertTrue(violations.stream().noneMatch(v -> v.message().contains("Address")),
                () -> "Injection-consumed models must not be reported: " + violations);
    }

    private SlingModelNode parse(String name, String source) throws Exception {
        Path file = root.resolve(name + ".java");
        Files.writeString(file, source);
        return new JavaSourceParser().parse(file).slingModels().get(0);
    }
}
