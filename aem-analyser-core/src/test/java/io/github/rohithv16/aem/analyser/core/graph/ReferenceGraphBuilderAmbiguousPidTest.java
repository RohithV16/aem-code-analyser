package io.github.rohithv16.aem.analyser.core.graph;

import io.github.rohithv16.aem.analyser.core.model.graph.ConfigFormat;
import io.github.rohithv16.aem.analyser.core.model.graph.EdgeType;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.parser.bytecode.DependencyBytecodeIndexer;
import io.github.rohithv16.aem.analyser.core.parser.config.OsgiConfigDefinition;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParseResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceGraphBuilderAmbiguousPidTest {

    private final Path root = Path.of("/mock/project");

    @Test
    void ambiguousLegacyHyphenResolvesToHyphenatedPidWhenOnlyTheWholeNameMatches() {
        OSGiComponentNode component = component("com.example.MyService", "my-service.pid-instance");
        Path configFile = root.resolve("ui.config/.../config/my-service.pid-instance.config");
        OsgiConfigDefinition definition = new OsgiConfigDefinition(
                configFile, "my-service.pid", "instance", true, true,
                Set.of(), ConfigFormat.FELIX_CONFIG, Map.of());

        ReferenceGraphBuilder.GraphBuildResult result = new ReferenceGraphBuilder().build(
                new ReferenceGraphBuilder.GraphInput(
                        List.of(),
                        List.of(parseResult(component)),
                        List.of(definition),
                        new DependencyBytecodeIndexer().index(List.of()),
                        root));

        OSGiConfigNode config = result.configNodes().values().iterator().next();
        assertEquals("my-service.pid-instance", config.getPid());
        assertFalse(config.isFactory());
        assertTrue(result.resolvedEdges().stream().anyMatch(edge ->
                        edge.getEdgeType() == EdgeType.CONFIGURES && edge.getTarget() == component),
                "Config should configure the hyphenated-PID component");
    }

    private JavaSourceParseResult parseResult(OSGiComponentNode component) {
        return new JavaSourceParseResult(root.resolve("MyService.java"), "com.example",
                List.of(), List.of(component), List.of(), List.of(),
                List.of(), List.of(), List.of(), Set.of());
    }

    private OSGiComponentNode component(String fqn, String name) {
        Path file = root.resolve(fqn.replace('.', '/') + ".java");
        return new OSGiComponentNode(
                fqn, name, JavaClassNode.Kind.CLASS,
                Collections.emptySet(), null, Collections.emptySet(),
                file, new SourceLocation(file, 1, 1),
                List.of(), OSGiComponentNode.ConfigurationPolicy.OPTIONAL,
                true, true, Collections.emptySet(), Map.of(),
                null, Collections.emptySet());
    }
}
