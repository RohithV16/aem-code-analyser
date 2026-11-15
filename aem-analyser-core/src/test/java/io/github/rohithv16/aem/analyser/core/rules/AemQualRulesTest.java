package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.java.AdministrativeCall;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual002Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual003Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual004Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual005Rule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AemQualRulesTest {

    private final Path root = Path.of("/mock/project");

    @Test
    void testAemQual001_pathBoundServlet() {
        OSGiComponentNode servlet = new OSGiComponentNode(
                "com.example.PathServlet", "com.example.PathServlet", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), "org.apache.sling.api.servlets.SlingSafeMethodsServlet",
                Collections.emptySet(), root.resolve("PathServlet.java"),
                new SourceLocation(root.resolve("PathServlet.java"), 12, 1),
                List.of(), OSGiComponentNode.ConfigurationPolicy.OPTIONAL,
                false, false, Set.of("javax.servlet.Servlet"),
                Map.of(), null, Set.of("sling.servlet.paths=/bin/my/service")
        );

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Map.of("com.example.PathServlet", servlet),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                List.of(), null
        );

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemQual001Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals(RuleId.AEM_QUAL_001.id(), violations.get(0).ruleId());
        assertEquals(Severity.WARNING, violations.get(0).severity());
        assertTrue(violations.get(0).message().contains("CQBP-75"));
    }

    @Test
    void testAemQual002_providerTypeImplementation() {
        JavaClassNode customPage = new JavaClassNode(
                "com.example.CustomPageImpl", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), null, Set.of("com.day.cq.wcm.api.Page"),
                root.resolve("CustomPageImpl.java"),
                new SourceLocation(root.resolve("CustomPageImpl.java"), 10, 1)
        );

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(),
                Map.of("com.example.CustomPageImpl", customPage),
                Collections.emptyMap(), List.of(), null
        );

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemQual002Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals(RuleId.AEM_QUAL_002.id(), violations.get(0).ruleId());
        assertEquals(Severity.ERROR, violations.get(0).severity());
        assertTrue(violations.get(0).message().contains("CQBP-84"));
    }

    @Test
    void testAemQual003_slingSchedulerUsage() {
        OSGiComponentNode scheduler = new OSGiComponentNode(
                "com.example.MyScheduler", "com.example.MyScheduler", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), null, Collections.emptySet(),
                root.resolve("MyScheduler.java"),
                new SourceLocation(root.resolve("MyScheduler.java"), 20, 1),
                List.of(), OSGiComponentNode.ConfigurationPolicy.OPTIONAL,
                false, false, Set.of("java.lang.Runnable"),
                Map.of(), null, Set.of("scheduler.expression=0 0 12 * * ?")
        );

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Map.of("com.example.MyScheduler", scheduler),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                List.of(), null
        );

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemQual003Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals(RuleId.AEM_QUAL_003.id(), violations.get(0).ruleId());
        assertEquals(Severity.WARNING, violations.get(0).severity());
        assertTrue(violations.get(0).message().contains("AMSCORE-554"));
    }

    @Test
    void testAemQual004_unsafeComponentState() {
        OSGiComponentNode.DeclaredField field = new OSGiComponentNode.DeclaredField(
                "resourceResolver", "org.apache.sling.api.resource.ResourceResolver",
                new SourceLocation(root.resolve("LeakyServlet.java"), 15, 5), false
        );

        OSGiComponentNode leakyServlet = new OSGiComponentNode(
                "com.example.LeakyServlet", "com.example.LeakyServlet", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), "org.apache.sling.api.servlets.SlingSafeMethodsServlet",
                Collections.emptySet(), root.resolve("LeakyServlet.java"),
                new SourceLocation(root.resolve("LeakyServlet.java"), 10, 1),
                List.of(), OSGiComponentNode.ConfigurationPolicy.OPTIONAL,
                false, false, Set.of("javax.servlet.Servlet"),
                Map.of(), null, Set.of("sling.servlet.resourceTypes=my/app/component"),
                List.of(field)
        );

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Map.of("com.example.LeakyServlet", leakyServlet),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                List.of(), null
        );

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemQual004Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals(RuleId.AEM_QUAL_004.id(), violations.get(0).ruleId());
        assertEquals(Severity.ERROR, violations.get(0).severity());
        assertTrue(violations.get(0).message().contains("resourceResolver"));
    }

    @Test
    void testAemQual005_administrativeAccessUsage() {
        AdministrativeCall call = new AdministrativeCall(
                "getAdministrativeResourceResolver", "com.example.LegacyService",
                root.resolve("LegacyService.java"),
                new SourceLocation(root.resolve("LegacyService.java"), 35, 12)
        );

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                List.of(), List.of(call), null
        );

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemQual005Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals(RuleId.AEM_QUAL_005.id(), violations.get(0).ruleId());
        assertEquals(Severity.ERROR, violations.get(0).severity());
        assertTrue(violations.get(0).message().contains("AMSCORE-553"));
    }
}
