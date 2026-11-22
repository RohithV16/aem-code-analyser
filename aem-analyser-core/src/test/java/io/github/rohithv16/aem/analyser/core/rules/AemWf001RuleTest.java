package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemWf001Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AEM-WF-001 against the cases from the issue: a launcher pointing at a model that exists nowhere
 * and a PROCESS step naming a class no bundle declares (two warnings), the same pair resolving (no
 * findings), and the ownership guards — an out-of-the-box model reference, a class from a vendor
 * bundle, and a content-only reactor that has no class index to check against.
 */
class AemWf001RuleTest {

    private static final String LAUNCHER = "ui.content/src/main/content/jcr_root/conf/global/settings/"
            + "workflow/launcher/zzprobe/.content.xml";
    private static final String MODEL = "ui.content/src/main/content/jcr_root/conf/global/settings/"
            + "workflow/models/probe-model/.content.xml";

    @TempDir
    Path root;

    @Test
    void missingModelAndMissingProcessClassAreReported() throws Exception {
        file(LAUNCHER, """
                <jcr:root xmlns:cq="http://www.day.com/jcr/cq/1.0"
                          jcr:primaryType="cq:WorkflowLauncher"
                          cq:workflowModel="/var/workflow/models/probe-missing"
                          glob="/content/probe/.*/jcr:content"/>
                """);
        file(MODEL, """
                <jcr:root jcr:primaryType="cq:WorkflowModel" jcr:title="Probe model">
                    <node0 jcr:primaryType="cq:WorkflowNode" type="PROCESS"
                           process="com.probe.steps.Gone" title="Gone"/>
                    <metaData jcr:primaryType="nt:unstructured"/>
                </jcr:root>
                """);

        List<Violation> violations = evaluate(classes("com.probe.Something"));

        assertEquals(2, violations.size(), () -> violations.toString());
        assertEquals(Set.of("MISSING_WORKFLOW_MODEL", "MISSING_PROCESS_CLASS"),
                violations.stream().map(Violation::subtype).collect(Collectors.toSet()));
        assertTrue(violations.stream().allMatch(v -> v.severity() == Severity.WARNING));
        Violation model = violations.stream()
                .filter(v -> v.subtype().equals("MISSING_WORKFLOW_MODEL")).findFirst().orElseThrow();
        assertTrue(model.message().contains("/var/workflow/models/probe-missing"), model.message());
        assertEquals(3, model.region().startLine(), "the finding points at cq:workflowModel");
        Violation step = violations.stream()
                .filter(v -> v.subtype().equals("MISSING_PROCESS_CLASS")).findFirst().orElseThrow();
        assertTrue(step.message().contains("probe-model") && step.message().contains("com.probe.steps.Gone"),
                step.message());
    }

    @Test
    void aLauncherAndStepThatResolveAreSilent() throws Exception {
        file(LAUNCHER, """
                <jcr:root xmlns:cq="http://www.day.com/jcr/cq/1.0"
                          jcr:primaryType="cq:WorkflowLauncher"
                          cq:workflowModel="/var/workflow/models/probe-model"/>
                """);
        file(MODEL, """
                <jcr:root jcr:primaryType="cq:WorkflowModel">
                    <node0 jcr:primaryType="cq:WorkflowNode" type="PROCESS"
                           process="com.probe.steps.PublishStep"/>
                </jcr:root>
                """);

        assertTrue(evaluate(classes("com.probe.steps.PublishStep")).isEmpty(),
                "A shipped model and a declared class resolve cleanly");
    }

    @Test
    void platformModelReferencesAreNotProjectModels() throws Exception {
        file("ui.content/src/main/content/jcr_root/conf/global/settings/workflow/launcher/dam/"
                + ".content.xml", """
                <jcr:root xmlns:cq="http://www.day.com/jcr/cq/1.0"
                          jcr:primaryType="cq:WorkflowLauncher"
                          cq:workflowModel="/var/workflow/models/dam/update_asset"/>
                """);
        file("ui.content/src/main/content/jcr_root/conf/global/settings/workflow/launcher/activation/"
                + ".content.xml", """
                <jcr:root xmlns:cq="http://www.day.com/jcr/cq/1.0"
                          jcr:primaryType="cq:WorkflowLauncher"
                          cq:workflowModel="/var/workflow/models/request_for_activation"/>
                """);

        assertTrue(evaluate(classes("com.probe.Something")).isEmpty(),
                "Out-of-the-box models are shipped by the product, not the project");
    }

    @Test
    void processClassFromAVendorBundleIsNotReported() throws Exception {
        file(MODEL, """
                <jcr:root jcr:primaryType="cq:WorkflowModel">
                    <node0 jcr:primaryType="cq:WorkflowNode" type="PROCESS"
                           process="com.vendor.steps.ReviewStep"/>
                </jcr:root>
                """);

        assertTrue(evaluate(classes("com.probe.Something")).isEmpty(),
                "Only packages the project itself declares are checked");
    }

    @Test
    void contentOnlyReactorIsNotJudged() throws Exception {
        file(MODEL, """
                <jcr:root jcr:primaryType="cq:WorkflowModel">
                    <node0 jcr:primaryType="cq:WorkflowNode" type="PROCESS"
                           process="com.probe.steps.Gone"/>
                </jcr:root>
                """);

        assertTrue(evaluate(List.of()).isEmpty(),
                "With no Java sources every process class comes from a bundle the analyser cannot see");
    }

    @Test
    void reactorWithoutWorkflowContentIsSilent() throws Exception {
        file("ui.apps/src/main/content/jcr_root/apps/probe/components/probe/.content.xml", """
                <jcr:root jcr:primaryType="cq:Component"/>
                """);

        assertTrue(evaluate(classes("com.probe.Something")).isEmpty());
    }

    private List<JavaClassNode> classes(String... fqns) throws Exception {
        List<JavaClassNode> nodes = new java.util.ArrayList<>();
        for (String fqn : fqns) {
            Path file = root.resolve("core/src/main/java/" + fqn.replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package " + fqn.substring(0, fqn.lastIndexOf('.'))
                    + ";\npublic class " + fqn.substring(fqn.lastIndexOf('.') + 1) + " {}\n");
            nodes.add(new JavaClassNode(fqn, JavaClassNode.Kind.CLASS, Set.of(), null, Set.of(), file,
                    new SourceLocation(file, 1, 1)));
        }
        return nodes;
    }

    private void file(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Violation> evaluate(List<JavaClassNode> classes) {
        ReferenceGraph graph = new ReferenceGraph();
        Map<String, JavaClassNode> classNodes = new LinkedHashMap<>();
        for (JavaClassNode node : classes) {
            graph.addVertex(node);
            classNodes.put(node.getFqn(), node);
        }
        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), classNodes,
                Collections.emptyMap(), List.of(), List.of(), null);
        RuleContext context = new RuleContext(graph, buildResult, Collections.emptySet(), null,
                Collections.emptySet(), root);
        return new AemWf001Rule().evaluate(context);
    }
}
