package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.ConfigFormat;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.SlingModelNode;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor;
import io.github.rohithv16.aem.analyser.core.model.report.CoverageReport;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDup001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDup002Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDup003Rule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateRulesTest {

    private final Path root = Path.of("/mock/project");

    @Test
    void testAemDup001_conflictingServletPaths() {
        OSGiComponentNode s1 = new OSGiComponentNode(
                "com.example.ServletA", "com.example.ServletA", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), "org.apache.sling.api.servlets.SlingSafeMethodsServlet",
                Collections.emptySet(), root.resolve("ServletA.java"),
                new SourceLocation(root.resolve("ServletA.java"), 10, 1),
                List.of(), OSGiComponentNode.ConfigurationPolicy.OPTIONAL,
                false, false, Set.of("javax.servlet.Servlet"),
                Map.of(), null, Set.of("sling.servlet.paths=/bin/my/endpoint")
        );

        OSGiComponentNode s2 = new OSGiComponentNode(
                "com.example.ServletB", "com.example.ServletB", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), "org.apache.sling.api.servlets.SlingSafeMethodsServlet",
                Collections.emptySet(), root.resolve("ServletB.java"),
                new SourceLocation(root.resolve("ServletB.java"), 15, 1),
                List.of(), OSGiComponentNode.ConfigurationPolicy.OPTIONAL,
                false, false, Set.of("javax.servlet.Servlet"),
                Map.of(), null, Set.of("sling.servlet.paths=/bin/my/endpoint")
        );

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Map.of("com.example.ServletA", s1, "com.example.ServletB", s2),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                List.of(), null
        );

        RuleContext ctx = new RuleContext(buildResult.graph(), buildResult, Collections.emptySet(), null, Collections.emptySet(), root);
        AemDup001Rule rule = new AemDup001Rule();
        List<Violation> violations = rule.evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals("AEM-DUP-001", violations.get(0).ruleId());
        assertTrue(violations.get(0).message().contains("/bin/my/endpoint"));
    }

    @Test
    void testAemDup002_duplicateOsgiConfig() {
        OSGiConfigNode c1 = new OSGiConfigNode(
                "cfg1", root.resolve("config/my.service~first.cfg.json"),
                new SourceLocation(root.resolve("config/my.service~first.cfg.json"), 1, 1),
                "my.service", "first", true, Set.of("author"), ConfigFormat.CFG_JSON
        );

        OSGiConfigNode c2 = new OSGiConfigNode(
                "cfg2", root.resolve("config/my.service~first.cfg.json"),
                new SourceLocation(root.resolve("config.author/my.service~first.cfg.json"), 1, 1),
                "my.service", "first", true, Set.of("author"), ConfigFormat.CFG_JSON
        );

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Map.of("c1", c1, "c2", c2),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), null
        );

        RuleContext ctx = new RuleContext(buildResult.graph(), buildResult, Collections.emptySet(), null, Collections.emptySet(), root);
        AemDup002Rule rule = new AemDup002Rule();
        List<Violation> violations = rule.evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals("AEM-DUP-002", violations.get(0).ruleId());
        assertTrue(violations.get(0).message().contains("my.service"));
    }

    @Test
    void testAemDup002_primaryLocationIsIndependentOfDiscoveryOrder() {
        OSGiConfigNode json = new OSGiConfigNode(
                "json", root.resolve("config/com.example.Service.cfg.json"),
                new SourceLocation(root.resolve("config/com.example.Service.cfg.json"), 1, 1),
                "com.example.Service", null, false, Set.of(), ConfigFormat.CFG_JSON
        );
        OSGiConfigNode config = new OSGiConfigNode(
                "config", root.resolve("config/com.example.Service.config"),
                new SourceLocation(root.resolve("config/com.example.Service.config"), 1, 1),
                "com.example.Service", null, false, Set.of(), ConfigFormat.PROPERTIES
        );

        String forward = primaryPath(List.of(json, config));
        String reverse = primaryPath(List.of(config, json));

        assertEquals(forward, reverse,
                () -> "Duplicate OSGi config reporting must not depend on discovery order");
        assertEquals("config/com.example.Service.config", forward);
    }

    private String primaryPath(List<OSGiConfigNode> configs) {
        java.util.Map<String, OSGiConfigNode> byId = new java.util.LinkedHashMap<>();
        configs.forEach(config -> byId.put(config.getId(), config));
        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), byId,
                Collections.emptyMap(), Collections.emptyMap(), List.of(), null
        );
        RuleContext ctx = new RuleContext(buildResult.graph(), buildResult, Collections.emptySet(), null,
                Collections.emptySet(), root);
        List<Violation> violations = new AemDup002Rule().evaluate(ctx);
        assertEquals(1, violations.size());
        return violations.get(0).filePath();
    }

    @Test
    void testAemDup003_duplicateSlingModel() {
        SlingModelNode m1 = new SlingModelNode(
                "com.example.HeaderA", Collections.emptySet(), null,
                Set.of("com.example.Header"), root.resolve("HeaderA.java"),
                new SourceLocation(root.resolve("HeaderA.java"), 1, 1),
                Set.of("org.apache.sling.api.resource.Resource"),
                Set.of("com.example.Header"), "my/cmp/header",
                SlingModelNode.DefaultInjectionStrategy.OPTIONAL, Collections.emptySet()
        );

        SlingModelNode m2 = new SlingModelNode(
                "com.example.HeaderB", Collections.emptySet(), null,
                Set.of("com.example.Header"), root.resolve("HeaderB.java"),
                new SourceLocation(root.resolve("HeaderB.java"), 1, 1),
                Set.of("org.apache.sling.api.resource.Resource"),
                Set.of("com.example.Header"), "my/cmp/header",
                SlingModelNode.DefaultInjectionStrategy.OPTIONAL, Collections.emptySet()
        );

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Map.of("m1", m1, "m2", m2), List.of(), null
        );

        RuleContext ctx = new RuleContext(buildResult.graph(), buildResult, Collections.emptySet(), null, Collections.emptySet(), root);
        AemDup003Rule rule = new AemDup003Rule();
        List<Violation> violations = rule.evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals("AEM-DUP-003", violations.get(0).ruleId());
        assertTrue(violations.get(0).message().contains("my/cmp/header"));
    }
}
