package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.discovery.FileInventory;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemA11y001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemA11y002Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AEM-A11Y-001/002 against the fixtures from the issue: one image with no alt, one anchor that is
 * an icon with no href, and the clean twin that differs only by the fix. The guards cover markup
 * that is not a DOM element (a JS string, a comment) and anchors that are valid without href.
 */
class AemA11yRuleTest {

    @TempDir
    Path root;

    @Test
    void imageWithoutAltAndIconAnchorWithoutHrefAreReported() throws Exception {
        write("a11y-broken.html", """
                <img src="${model.src}">
                <a class="cta"><img src="i.svg" alt=""></a>
                """);

        assertEquals(1, evaluate001().size(),
                () -> "Exactly one img has no alt: " + evaluate001());
        List<Violation> anchors = evaluate002();
        assertEquals(1, anchors.size(), () -> "Exactly one anchor is not a usable link: " + anchors);
        assertEquals("ANCHOR_WITHOUT_HREF", anchors.get(0).subtype());
        assertEquals(1, evaluate001().get(0).region().startLine(), "the img is reported on its line");
        assertEquals(2, anchors.get(0).region().startLine(), "the anchor is reported on its line");
    }

    @Test
    void accessibleMarkupIsSilent() throws Exception {
        write("a11y-ok.html", """
                <img src="${model.src}" alt="${model.alt}">
                <img src="divider.svg" alt="">
                <img src="deco.svg" aria-hidden="true">
                <img src="presentational.svg" role="presentation">
                <a href="${model.url}"><img src="logo.svg" alt="${model.logoAlt}"/></a>
                <a href="/about">About us</a>
                <a name="top"></a>
                <a href="#" aria-label="Open search"><svg><title>Search</title></svg></a>
                """);

        assertTrue(evaluate001().isEmpty(), () -> "No image is missing alt: " + evaluate001());
        assertTrue(evaluate002().isEmpty(), () -> "No anchor is defective: " + evaluate002());
    }

    @Test
    void markupInsideScriptsAndCommentsIsNotAnElement() throws Exception {
        write("a11y-js.html", """
                <script>
                  var html = '<a href="' + url + '">' + title + '</a>';
                  var img = '<img src="' + src + '">';
                </script>
                <!-- <img src="commented-out.png"> -->
                <!-- <a class="legacy"></a> -->
                <a href="/real">A real link</a>
                """);

        assertTrue(evaluate001().isEmpty(),
                () -> "An img inside a JS string or a comment is not markup: " + evaluate001());
        assertTrue(evaluate002().isEmpty(),
                () -> "An anchor inside a JS string or a comment is not markup: " + evaluate002());
    }

    @Test
    void iconLinkWithAccessibleNameIsSilent() throws Exception {
        write("icon-link.html", """
                <a href="${model.url}" aria-label="${model.label}"><img src="icon.svg" alt=""></a>
                <a href="${model.url}" aria-label="Menu"><span class="icon"></span></a>
                """);

        assertTrue(evaluate002().isEmpty(), () -> "A named icon link must stay silent: " + evaluate002());
    }

    private void write(String name, String content) throws Exception {
        Path components = root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/components/zzprobe");
        Files.createDirectories(components);
        Files.writeString(components.resolve(name), content);
    }

    private List<Violation> evaluate001() {
        return new AemA11y001Rule().evaluate(context());
    }

    private List<Violation> evaluate002() {
        return new AemA11y002Rule().evaluate(context());
    }

    private RuleContext context() {
        ReferenceGraph graph = new ReferenceGraph();
        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), List.of(), List.of(), null);
        return new RuleContext(graph, buildResult, Collections.emptySet(), null,
                Collections.emptySet(), root, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), FileInventory.scan(root));
    }
}
