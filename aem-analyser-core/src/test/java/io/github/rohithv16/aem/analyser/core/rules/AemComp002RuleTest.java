package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.discovery.HtlScanner;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemComp002Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AEM-COMP-002 must flag a literal resource type that names no component in the project's own
 * {@code /apps} namespace, and stay silent for platform types, other projects' types and
 * {@code target/} copies.
 */
class AemComp002RuleTest {

    @TempDir
    Path root;

    @Test
    void missingOwnComponentIsFlagged() throws Exception {
        writeComponent("acme", "components/card");
        writeContent("ui.content/src/main/content/jcr_root/content/acme/en/.content.xml", """
                <jcr:root xmlns:sling="http://sling.apache.org/jcr/sling/1.0">
                    <hero sling:resourceType="acme/components/card"/>
                    <ghost sling:resourceType="acme/components/missing"/>
                </jcr:root>
                """);

        List<Violation> violations = evaluate();

        assertEquals(1, violations.size(), () -> "Exactly the missing type must be reported: " + violations);
        assertEquals(RuleId.AEM_COMP_002.id(), violations.get(0).ruleId());
        assertEquals("MISSING_RESOURCE_TYPE", violations.get(0).subtype());
        assertTrue(violations.get(0).message().contains("acme/components/missing"),
                () -> "The message must name the unresolved type: " + violations.get(0).message());
    }

    @Test
    void existingComponentAndPlatformPrefixesAreSilent() throws Exception {
        writeComponent("acme", "components/card");
        writeContent("ui.content/src/main/content/jcr_root/content/acme/en/.content.xml", """
                <jcr:root xmlns:sling="http://sling.apache.org/jcr/sling/1.0">
                    <a sling:resourceType="acme/components/card"/>
                    <b sling:resourceType="cq/experience-fragments/components/experiencefragment"/>
                    <c sling:resourceType="granite/ui/components/coral/foundation/container"/>
                    <d sling:resourceType="core/wcm/components/text/v2/text"/>
                    <e sling:resourceType="wcm/foundation/components/text"/>
                    <f sling:resourceType="foundation/components/parbase"/>
                </jcr:root>
                """);

        assertTrue(evaluate().isEmpty(), "Platform and resolvable types must not be reported");
    }

    @Test
    void foreignPrefixAndTargetCopiesAreIgnored() throws Exception {
        writeComponent("acme", "components/card");
        writeContent("ui.content/src/main/content/jcr_root/content/other/en/.content.xml", """
                <jcr:root xmlns:sling="http://sling.apache.org/jcr/sling/1.0">
                    <a sling:resourceType="otherapp/components/card"/>
                </jcr:root>
                """);
        // A stale build copy must never produce a finding.
        writeContent("ui.apps/target/classes/jcr_root/apps/acme/components/card/.content.xml", """
                <jcr:root xmlns:sling="http://sling.apache.org/jcr/sling/1.0">
                    <a sling:resourceType="acme/components/ghost-from-target"/>
                </jcr:root>
                """);

        assertTrue(evaluate().isEmpty(),
                "A type outside the project's /apps namespace and a target/ copy must not be reported");
    }

    @Test
    void staticDataSlyResourceTypeIsFlaggedAndDynamicIsNot() throws Exception {
        writeComponent("acme", "components/card");
        writeTemplate("ui.apps/src/main/content/jcr_root/apps/acme/components/card/card.html", """
                <div>
                    <sly data-sly-resource="${ @ resourceType='acme/components/ghost'}"/>
                    <sly data-sly-resource="${ @ resourceType=model.dynamicType}"/>
                    <sly data-sly-resource="${ @ resourceType='acme/components/card'}"/>
                </div>
                """);

        List<Violation> violations = evaluate();

        assertEquals(1, violations.size(), () -> "Only the literal unresolvable type is judged: " + violations);
        assertTrue(violations.get(0).filePath().endsWith("card.html"),
                () -> "Finding must point at the including template: " + violations.get(0).filePath());
        assertEquals(2, violations.get(0).region().startLine(),
                "Finding must point at the data-sly-resource line");
    }

    private void writeComponent(String prefix, String relative) throws Exception {
        Path dir = root.resolve("ui.apps/src/main/content/jcr_root/apps/" + prefix + "/" + relative);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".content.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <jcr:root xmlns:cq="http://www.day.com/jcr/cq/1.0" jcr:primaryType="cq:Component"/>
                """);
    }

    private void writeContent(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void writeTemplate(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Violation> evaluate() {
        HtlScanner.HtlScanResult scan = new HtlScanner().scan(root);
        ReferenceGraph graph = new ReferenceGraph();
        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), List.of(), List.of(), null);
        RuleContext context = new RuleContext(graph, buildResult, Collections.emptySet(), null,
                Collections.emptySet(), root, scan.usages(), List.of(), scan.unsafeContexts(), List.of(),
                scan.includes(), scan.resources());
        return new AemComp002Rule().evaluate(context);
    }
}
