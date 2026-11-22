package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.discovery.HtlScanner;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlUsage;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead004Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AEM-DEAD-004 must only report a Java use-API target that is genuinely absent from the project.
 * Resource targets and dynamic expressions are valid HTL and must never be flagged.
 */
class AemDead004RuleTest {

    @TempDir
    Path root;

    @Test
    void validHtlUseApiFormsAreNotFlagged() throws IOException {
        List<Violation> violations = evaluate("""
                <div>
                    <sly data-sly-use.model="com.acme.models.HeroModel" />
                    <sly data-sly-use.card="${'com.acme.models.ProductCard' @ sku='ABC-123'}" />
                    <sly data-sly-use.dyn="${component.modelClass}" />
                    <sly data-sly-use.data="data.json" />
                    <h1>${model.title}</h1>
                </div>
                """);

        assertTrue(violations.isEmpty(),
                () -> "Valid HTL use-API forms must not be reported: " + violations);
    }

    @Test
    void missingFullyQualifiedClassIsFlagged() throws IOException {
        List<Violation> violations = evaluate("""
                <sly data-sly-use.model="com.acme.models.MissingModel" />
                """);

        assertEquals(1, violations.size(), () -> "Expected exactly one finding: " + violations);
        assertTrue(violations.get(0).message().contains("com.acme.models.MissingModel"),
                () -> "Message should name the missing class: " + violations.get(0).message());
    }

    @Test
    void unresolvedBareClassNameIsFlagged() throws IOException {
        List<Violation> violations = evaluate("""
                <sly data-sly-use.model="HeroModl" />
                """);

        assertEquals(1, violations.size(), () -> "Expected exactly one finding: " + violations);
        assertTrue(violations.get(0).message().contains("HeroModl"),
                () -> "Message should name the unresolved class: " + violations.get(0).message());
    }

    @Test
    void platformClassIsExempt() throws IOException {
        List<Violation> violations = evaluate("""
                <sly data-sly-use.tabs="com.adobe.cq.wcm.core.components.models.Tabs" />
                """);

        assertTrue(violations.isEmpty(),
                () -> "Platform classes are provided by AEM and must stay exempt: " + violations);
    }

    private List<Violation> evaluate(String htl) throws IOException {
        Path template = root.resolve("component.html");
        Files.writeString(template, htl);

        List<HtlUsage> usages = new HtlScanner().scanDirectory(root);
        assertEquals(htl.split("data-sly-use", -1).length - 1, usages.size(),
                "Scanner must see every data-sly-use attribute in the fixture");

        ReferenceGraph graph = new ReferenceGraph();
        Map<String, JavaClassNode> classNodes = new LinkedHashMap<>();
        for (String fqn : List.of("com.acme.models.HeroModel", "com.acme.models.ProductCard")) {
            classNodes.put(fqn, new JavaClassNode(fqn, JavaClassNode.Kind.CLASS,
                    Collections.emptySet(), null, Collections.emptySet(),
                    root.resolve("stub").resolve(fqn.replace('.', '/') + ".java"),
                    SourceLocation.wholeFile(root.resolve("stub"))));
        }

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), classNodes, Collections.emptyMap(),
                List.of(), List.of(), null);
        RuleContext context = new RuleContext(graph, buildResult, Collections.emptySet(), null,
                Collections.emptySet(), root, usages, List.of(), List.of());

        return new AemDead004Rule().evaluate(context);
    }
}
