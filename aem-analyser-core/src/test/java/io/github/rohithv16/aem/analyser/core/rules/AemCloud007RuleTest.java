package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud007Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AEM-CLOUD-007 against the cases from the issue: one root shipped by two modules (with and without
 * {@code replace}), a platform root, and the legitimate self-overlap the portfolio's ui.content uses,
 * which must not be reported.
 */
class AemCloud007RuleTest {

    private static final String FILTER = "src/main/content/META-INF/vault/filter.xml";

    @TempDir
    Path root;

    @Test
    void sharedRootWithReplaceIsReportedOncePerShape() throws Exception {
        filter("ui.apps", """
                <workspaceFilter version="1.0">
                    <filter root="/content/acme" mode="replace"/>
                </workspaceFilter>
                """);
        filter("ui.content", """
                <workspaceFilter version="1.0">
                    <filter root="/content/acme" mode="merge"/>
                </workspaceFilter>
                """);

        List<Violation> violations = evaluate();

        assertEquals(2, violations.size(), () -> "One overlap and one replace: " + violations);
        Set<String> subtypes = violations.stream().map(Violation::subtype).collect(Collectors.toSet());
        assertEquals(Set.of("OVERLAPPING_FILTER_ROOT", "REPLACE_ON_SHARED_ROOT"), subtypes);
        Violation overlap = violations.stream()
                .filter(v -> v.subtype().equals("OVERLAPPING_FILTER_ROOT")).findFirst().orElseThrow();
        assertTrue(overlap.message().contains("ui.apps") && overlap.message().contains("ui.content"),
                () -> "The message must name both modules: " + overlap.message());
        assertEquals(1, overlap.relatedLocations().size(), "the overlap points at the other package");
    }

    @Test
    void selfOverlapInsideOneModuleIsNotCrossModuleOverlap() throws Exception {
        // Mirrors the portfolio's ui.content: a broad root plus a child root with a blanket exclude.
        filter("ui.content", """
                <workspaceFilter version="1.0">
                    <filter root="/conf/acme" mode="merge"/>
                    <filter root="/content/acme" mode="merge"/>
                    <filter root="/content/dam/acme" mode="merge">
                        <exclude pattern="/content/dam/acme(/.*)?"/>
                        <include pattern="/content/dam/acme/jcr:content(/.*)?"/>
                    </filter>
                    <filter root="/content/dam/acme/asset.jpg" mode="merge"/>
                </workspaceFilter>
                """);

        assertTrue(evaluate().isEmpty(),
                "A module rooting a parent and a child path is filter composition, not an install race");
    }

    @Test
    void platformRootsAreReported() throws Exception {
        filter("ui.content", """
                <workspaceFilter version="1.0">
                    <filter root="/apps"/>
                    <filter root="/content/dam"/>
                    <filter root="/libs/cq/security"/>
                    <filter root="/apps/acme/components"/>
                </workspaceFilter>
                """);

        List<Violation> violations = evaluate();

        assertEquals(3, violations.size(), () -> "Bare /apps, bare /content/dam and the /libs subtree: "
                + violations);
        assertTrue(violations.stream().allMatch(v -> v.subtype().equals("ROOT_OUTSIDE_PROJECT_NAMESPACE")));
        Set<String> roots = violations.stream()
                .map(v -> v.message().replaceAll(".*root '([^']+)'.*", "$1"))
                .collect(Collectors.toSet());
        assertEquals(Set.of("/apps", "/content/dam", "/libs/cq/security"), roots);
    }

    @Test
    void buildOutputCopiesAreNotComparedWithAuthoredFilters() throws Exception {
        filter("ui.apps", """
                <workspaceFilter version="1.0">
                    <filter root="/content/acme"/>
                </workspaceFilter>
                """);
        Path copy = root.resolve("ui.content/target/classes/" + FILTER);
        Files.createDirectories(copy.getParent());
        Files.writeString(copy, """
                <workspaceFilter version="1.0">
                    <filter root="/content/acme" mode="replace"/>
                </workspaceFilter>
                """);
        Path vaultWork = root.resolve("ui.config/src/main/content/vault-work/META-INF/vault/filter.xml");
        Files.createDirectories(vaultWork.getParent());
        Files.writeString(vaultWork, """
                <workspaceFilter version="1.0">
                    <filter root="/apps/acme/osgiconfig"/>
                </workspaceFilter>
                """);

        assertTrue(evaluate().isEmpty(),
                "target/ and vault-work/ copies repeat an authored filter and must not create overlaps");
    }

    @Test
    void malformedFilterXmlDoesNotFailTheRun() throws Exception {
        filter("ui.apps", "<workspaceFilter><filter root=\"/content/acme\">\n");

        assertTrue(evaluate().isEmpty());
    }

    @Test
    void projectWithoutContentModulesIsSilent() throws Exception {
        Files.createDirectories(root.resolve("core/src/main/java/com/acme"));
        assertTrue(evaluate().isEmpty());
    }

    private void filter(String module, String body) throws Exception {
        Path file = root.resolve(module + "/" + FILTER);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    private List<Violation> evaluate() {
        ReferenceGraph graph = new ReferenceGraph();
        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), List.of(), List.of(), null);
        RuleContext context = new RuleContext(graph, buildResult, Collections.emptySet(), null,
                Collections.emptySet(), root);
        return new AemCloud007Rule().evaluate(context);
    }
}
