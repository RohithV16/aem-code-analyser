package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.ConfigFormat;
import io.github.rohithv16.aem.analyser.core.model.graph.DataType;
import io.github.rohithv16.aem.analyser.core.model.graph.EdgeType;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.edge.SimpleEdge;
import io.github.rohithv16.aem.analyser.core.model.graph.node.ConfigPropertyNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiOcdNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OcdPropertyMethodNode;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead002ARule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AemDead002ARuleTest {

    private final Path root = Path.of("/mock/project");

    @Test
    void propertyDeclaredByAnyBoundComponentIsNotGhost() {
        Fixture fixture = new Fixture();
        fixture.config("beta", "beta");

        List<Violation> violations = new AemDead002ARule().evaluate(fixture.context());

        assertTrue(violations.isEmpty(), () -> "Unexpected violations: " + violations);
    }

    @Test
    void propertyDeclaredByNoBoundComponentIsGhost() {
        Fixture fixture = new Fixture();
        fixture.config("gamma", "gamma");

        List<Violation> violations = new AemDead002ARule().evaluate(fixture.context());

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message().contains("'gamma'"));
    }

    @Test
    void explicitAttributeDefinitionIdIsHonoured() {
        Fixture fixture = new Fixture();
        fixture.ocdB.addPropertyMethod(method("feature.flag", "featureFlag"));
        fixture.config("feature.flag", "feature.flag");

        List<Violation> violations = new AemDead002ARule().evaluate(fixture.context());

        assertTrue(violations.isEmpty(), () -> "Unexpected violations: " + violations);
    }

    @Test
    void propertyNamesAreCaseSensitive() {
        Fixture fixture = new Fixture();
        fixture.config("Alpha", "Alpha");

        List<Violation> violations = new AemDead002ARule().evaluate(fixture.context());

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message().contains("'Alpha'"));
    }

    @Test
    void configBoundToComponentWithoutOcdIsSkipped() {
        Fixture fixture = new Fixture();
        OSGiComponentNode plain = component("com.example.Plain", null);
        fixture.graph.addVertex(plain);
        fixture.graph.addEdge(new SimpleEdge(fixture.config, plain, EdgeType.CONFIGURES, "configures"));
        fixture.config("gamma", "gamma");

        List<Violation> violations = new AemDead002ARule().evaluate(fixture.context());

        assertTrue(violations.isEmpty(), () -> "Unexpected violations: " + violations);
    }

    private OSGiComponentNode component(String fqn, String designatedOcdFqn) {
        Path file = root.resolve(fqn.replace('.', '/') + ".java");
        return new OSGiComponentNode(
                fqn, fqn, JavaClassNode.Kind.CLASS,
                Collections.emptySet(), null, Collections.emptySet(),
                file, new SourceLocation(file, 1, 1),
                List.of(), OSGiComponentNode.ConfigurationPolicy.OPTIONAL,
                true, true, Collections.emptySet(), Map.of(),
                designatedOcdFqn, Collections.emptySet());
    }

    private OcdPropertyMethodNode method(String normalizedName, String methodName) {
        Path file = root.resolve("Config.java");
        return new OcdPropertyMethodNode(
                "com.example.SharedConfig", methodName, null, normalizedName,
                "String", List.of(), false, file, new SourceLocation(file, 1, 1));
    }

    private final class Fixture {
        final ReferenceGraph graph = new ReferenceGraph();
        final OSGiOcdNode ocdA = new OSGiOcdNode(
                "com.example.ConfigA", "com.example.ConfigA", "Config A", Collections.emptySet(),
                root.resolve("ConfigA.java"), new SourceLocation(root.resolve("ConfigA.java"), 1, 1));
        final OSGiOcdNode ocdB = new OSGiOcdNode(
                "com.example.ConfigB", "com.example.ConfigB", "Config B", Collections.emptySet(),
                root.resolve("ConfigB.java"), new SourceLocation(root.resolve("ConfigB.java"), 1, 1));
        final OSGiConfigNode config;

        Fixture() {
            ocdA.addPropertyMethod(method("alpha", "alpha"));
            ocdB.addPropertyMethod(method("beta", "beta"));
            graph.getIndex().registerOcd(ocdA);
            graph.getIndex().registerOcd(ocdB);

            OSGiComponentNode a = component("com.example.ComponentA", ocdA.getOcdFqn());
            OSGiComponentNode b = component("com.example.ComponentB", ocdB.getOcdFqn());
            Path configFile = root.resolve("ui.config/src/jcr_root/apps/sample/osgiconfig/config/com.example.pid.cfg.json");
            config = new OSGiConfigNode(
                    "com.example.Config", configFile, SourceLocation.wholeFile(configFile),
                    "com.example.Config", null, false, Set.of(), ConfigFormat.CFG_JSON);
            graph.addEdge(new SimpleEdge(config, a, EdgeType.CONFIGURES, "configures"));
            graph.addEdge(new SimpleEdge(config, b, EdgeType.CONFIGURES, "configures"));
        }

        void config(String key, String rawKey) {
            Path file = config.getFilePath();
            config.addProperty(new ConfigPropertyNode(
                    config.getPid(), file, new SourceLocation(file, 2, 3),
                    key, rawKey, null, "value", DataType.STRING, false));
        }

        RuleContext context() {
            ReferenceGraphBuilder.GraphBuildResult result = new ReferenceGraphBuilder.GraphBuildResult(
                    graph, Collections.emptySet(), Collections.emptySet(),
                    Map.of(), Map.of(config.getPid(), config), Collections.emptyMap(), Collections.emptyMap(),
                    List.of(), List.of(), List.of(), List.of(), null);
            return new RuleContext(graph, result, Set.of(), null, Set.of(), root);
        }
    }
}
