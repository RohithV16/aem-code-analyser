package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.ConfigFormat;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud004Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud005Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead005Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemPerf004Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual006Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemSec002Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddedRulesTest {

    @TempDir
    Path root;

    private RuleContext context(Map<String, OSGiComponentNode> components,
                                Map<String, OSGiConfigNode> configs,
                                Map<String, JavaClassNode> classes) {
        return context(components, configs, classes, List.of());
    }

    private RuleContext context(Map<String, OSGiComponentNode> components,
                                Map<String, OSGiConfigNode> configs,
                                Map<String, JavaClassNode> classes,
                                List<io.github.rohithv16.aem.analyser.core.model.perf.QueryTraversalFinding> traversals) {
        ReferenceGraph graph = new ReferenceGraph();
        components.values().forEach(graph::addVertex);
        configs.values().forEach(graph::addVertex);
        classes.values().forEach(graph::addVertex);
        ReferenceGraphBuilder.GraphBuildResult result = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(), components, configs, classes,
                Collections.emptyMap(), List.of(), List.of(), List.of(), traversals, null);
        return new RuleContext(graph, result, Collections.emptySet(), null, Collections.emptySet(), root);
    }

    @Test
    void resolverStoredInFieldIsFlagged() {
        Path file = root.resolve("Service.java");
        SourceLocation location = new SourceLocation(file, 4, 5);
        OSGiComponentNode component = new OSGiComponentNode(
                "com.example.Service", "com.example.Service", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), null, Collections.emptySet(), file, location,
                List.of(), OSGiComponentNode.ConfigurationPolicy.OPTIONAL, true, true,
                Collections.emptySet(), Collections.emptyMap(), null, Collections.emptySet(),
                List.of(new OSGiComponentNode.DeclaredField(
                        "resolver", "org.apache.sling.api.resource.ResourceResolver", location, false)));

        List<Violation> violations = new AemQual006Rule().evaluate(
                context(Map.of(component.getFqn(), component), Map.of(), Map.of()));

        assertEquals(1, violations.size());
        assertEquals("REQUEST_SCOPED_FIELD", violations.get(0).subtype());
    }

    @Test
    void invalidRunmodeFolderIsFlagged() {
        Path invalid = root.resolve("ui.config/.../osgiconfig/config.foo/com.example.Service.cfg.json");
        Path valid = root.resolve("ui.config/.../osgiconfig/config.author/com.example.Service.cfg.json");
        OSGiConfigNode bad = new OSGiConfigNode(invalid.toString(), invalid, null,
                "com.example.Service", null, false, Set.of("foo"), ConfigFormat.CFG_JSON);
        OSGiConfigNode good = new OSGiConfigNode(valid.toString(), valid, null,
                "com.example.Service", null, false, Set.of("author"), ConfigFormat.CFG_JSON);

        List<Violation> violations = new AemCloud005Rule().evaluate(
                context(Map.of(), Map.of(bad.getId(), bad, good.getId(), good), Map.of()));

        assertEquals(1, violations.size());
        assertEquals("INVALID_RUNMODE_FOLDER", violations.get(0).subtype());
    }

    @Test
    void deprecatedImportIsFlagged() throws Exception {        Path file = root.resolve("Legacy.java");
        Files.writeString(file, """
                package com.example;
                import org.apache.commons.lang.StringUtils;
                public class Legacy { }
                """);
        JavaClassNode node = new JavaClassNode("com.example.Legacy", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), null, Collections.emptySet(), file, new SourceLocation(file, 1, 1));

        List<Violation> violations = new AemCloud004Rule().evaluate(
                context(Map.of(), Map.of(), Map.of(node.getFqn(), node)));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message().contains("org.apache.commons.lang.StringUtils"));
    }

    @Test
    void openRedirectAndUnboundedQueryAreFlagged() throws Exception {
        Path file = root.resolve("Servlet.java");
        Files.writeString(file, """
                package com.example;
                class Servlet {
                    void run(Object request, Object response, Object session) throws Exception {
                        response.sendRedirect(request.getParameter("next"));
                        session.createQuery("select * from nt:base", "JCR-SQL2");
                    }
                }
                """);
        JavaClassNode node = new JavaClassNode("com.example.Servlet", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), null, Collections.emptySet(), file, new SourceLocation(file, 1, 1));
        RuleContext context = context(Map.of(), Map.of(), Map.of(node.getFqn(), node),
                List.of(new io.github.rohithv16.aem.analyser.core.model.perf.QueryTraversalFinding(
                        file, 5, 21, "select * from nt:base", "JCR-SQL2")));

        assertEquals(1, new AemSec002Rule().evaluate(context).size());
        assertEquals(1, new AemPerf004Rule().evaluate(context).size());
    }

    @Test
    void orphanedClientlibCategoryIsFlagged() throws Exception {
        Path orphan = root.resolve("ui.apps/clientlibs/orphan/.content.xml");
        Path used = root.resolve("ui.apps/clientlibs/used/.content.xml");
        Files.createDirectories(orphan.getParent());
        Files.createDirectories(used.getParent());
        Files.writeString(orphan, "<jcr:root categories=\"[orphan.category]\"/>");
        Files.writeString(used, "<jcr:root categories=\"[used.category]\"/>");
        Files.createDirectories(root.resolve("ui.apps/components/a"));
        Files.writeString(root.resolve("ui.apps/components/a/a.html"),
                "<sly data-sly-use.clientlib=\"used.category\"/>");

        List<Violation> violations = new AemDead005Rule().evaluate(
                context(Map.of(), Map.of(), Map.of()));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message().contains("orphan.category"));
    }
}
