package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParser;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead003CRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AemDead003CRuleTest {

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
        RuleContext context = new RuleContext(graph, result, Collections.emptySet(), null,
                Collections.emptySet(), root);
        return new AemDead003CRule().evaluate(context);
    }

    @Test
    void plainDelayedComponentIsInactive() throws Exception {
        List<Violation> violations = evaluate("Plain", """
                package com.example;
                import org.osgi.service.component.annotations.Component;
                @Component(immediate = false)
                public class Plain { }
                """);

        assertEquals(1, violations.size());
        assertEquals("INACTIVE_DELAYED_COMPONENT", violations.get(0).subtype());
    }

    @Test
    void factoryComponentIsNotInactive() throws Exception {
        List<Violation> violations = evaluate("Factory", """
                package com.example;
                import org.osgi.service.component.annotations.Component;
                @Component(immediate = false, factory = "com.example.factory")
                public class Factory { }
                """);

        assertTrue(violations.isEmpty(), violations::toString);
    }

    @Test
    void disabledComponentIsNotInactive() throws Exception {
        List<Violation> violations = evaluate("Disabled", """
                package com.example;
                import org.osgi.service.component.annotations.Component;
                @Component(immediate = false, enabled = false)
                public class Disabled { }
                """);

        assertTrue(violations.isEmpty(), violations::toString);
    }

    @Test
    void requirePolicyComponentIsNotInactive() throws Exception {
        List<Violation> violations = evaluate("Required", """
                package com.example;
                import org.osgi.service.component.annotations.Component;
                import org.osgi.service.component.annotations.ConfigurationPolicy;
                @Component(immediate = false, configurationPolicy = ConfigurationPolicy.REQUIRE)
                public class Required { }
                """);

        assertTrue(violations.isEmpty(), violations::toString);
    }

    @Test
    void componentUsingBundleContextIsNotInactive() throws Exception {
        List<Violation> violations = evaluate("Tracked", """
                package com.example;
                import org.osgi.framework.BundleContext;
                import org.osgi.service.component.annotations.Component;
                @Component(immediate = false)
                public class Tracked {
                    private BundleContext context;
                }
                """);

        assertTrue(violations.isEmpty(), violations::toString);
    }
}
