package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.discovery.HtlScanner;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead007Rule;
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
 * AEM-DEAD-007 fixture from the rule issue: {@code a -> b -> a} is one cycle, {@code c} includes a
 * missing template, and a dynamic include is invisible to static analysis. Exactly two warnings.
 */
class AemDead007RuleTest {

    @TempDir
    Path root;

    @Test
    void cycleAndMissingIncludeAreReportedExactlyOnce() throws Exception {
        Path inc = root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/components/zzprobe/inc");
        Files.createDirectories(inc);
        Files.writeString(inc.resolve("a.html"), "<sly data-sly-include=\"b.html\"/>\n");
        Files.writeString(inc.resolve("b.html"), "<sly data-sly-include=\"a.html\"/>\n");
        Files.writeString(inc.resolve("c.html"), "<sly data-sly-include=\"missing.html\"/>\n");
        Files.writeString(inc.resolve("d.html"),
                "<sly data-sly-include=\"${model.tpl}\"/>\n");

        List<Violation> violations = evaluate();

        assertEquals(2, violations.size(), () -> "Expected one cycle and one missing include: " + violations);
        Set<String> subtypes = violations.stream().map(Violation::subtype).collect(Collectors.toSet());
        assertEquals(Set.of("HTL_INCLUDE_CYCLE", "BROKEN_HTL_INCLUDE"), subtypes);
        assertEquals(1, violations.stream().filter(v -> v.subtype().equals("HTL_INCLUDE_CYCLE")).count());
        assertEquals(1, violations.stream().filter(v -> v.subtype().equals("BROKEN_HTL_INCLUDE")).count());
        assertTrue(violations.stream().anyMatch(v -> v.filePath().endsWith("c.html")),
                () -> "The missing include must be reported where it is included: " + violations);
    }

    @Test
    void resolvingIncludesAndAbsoluteLibTargetsAreSilent() throws Exception {
        Path inc = root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/components/card");
        Files.createDirectories(inc);
        Files.writeString(inc.resolve("card.html"), """
                <sly data-sly-include="card-details.html"/>
                <sly data-sly-include="/libs/core/wcm/components/text/v2/text/text.html"/>
                """);
        Files.writeString(inc.resolve("card-details.html"), "<p>details</p>\n");

        assertTrue(evaluate().isEmpty(),
                "An existing sibling include and a /libs target must not be reported");
    }

    @Test
    void selfIncludeIsASingleCycle() throws Exception {
        Path inc = root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/components/loop");
        Files.createDirectories(inc);
        Files.writeString(inc.resolve("self.html"), "<sly data-sly-include=\"self.html\"/>\n");

        List<Violation> violations = evaluate();

        assertEquals(1, violations.size(), () -> "A self-include is one cycle edge: " + violations);
        assertEquals("HTL_INCLUDE_CYCLE", violations.get(0).subtype());
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
        return new AemDead007Rule().evaluate(context);
    }
}
