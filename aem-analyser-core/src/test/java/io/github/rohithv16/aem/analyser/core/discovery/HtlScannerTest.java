package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.htl.HtlUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HtlScannerTest {

    @Test
    void testExtractsDataSlyUse(@TempDir Path tempDir) throws IOException {
        String htlContent = """
                <div data-sly-use.tabs="com.adobe.cq.wcm.core.components.models.Tabs"
                     data-sly-use.templates="core/wcm/components/commons/v1/templates.html">
                    <p data-sly-use.myModel="com.example.core.models.MyModel">${myModel.title}</p>
                    <sly data-sly-use.expr="${'com.example.core.models.ExprModel'}" />
                    <sly data-sly-use.script="nav.js" />
                </div>
                """;
        Path htlFile = tempDir.resolve("test.html");
        Files.writeString(htlFile, htlContent);

        HtlScanner scanner = new HtlScanner();
        List<HtlUsage> usages = scanner.scanDirectory(tempDir);

        assertEquals(5, usages.size());

        // Check Tabs
        assertTrue(usages.stream().anyMatch(u -> "com.adobe.cq.wcm.core.components.models.Tabs".equals(u.target())
                && "tabs".equals(u.identifier()) && u.isJavaClass()));

        // Check templates.html
        assertTrue(usages.stream().anyMatch(u -> "core/wcm/components/commons/v1/templates.html".equals(u.target())
                && !u.isJavaClass()));

        // Check MyModel
        assertTrue(usages.stream().anyMatch(u -> "com.example.core.models.MyModel".equals(u.target())
                && "myModel".equals(u.identifier()) && u.isJavaClass()));

        // Check ExprModel
        assertTrue(usages.stream().anyMatch(u -> "com.example.core.models.ExprModel".equals(u.target())
                && u.isJavaClass()));

        // Check nav.js
        assertTrue(usages.stream().anyMatch(u -> "nav.js".equals(u.target())
                && !u.isJavaClass()));
    }

    @Test
    void testDetectsMultilineDataSlyUse(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("multi.html"), """
                <div data-sly-use.model=
                     "com.example.core.models.MultiLineModel">
                    ${model.title}
                </div>
                """);

        List<HtlUsage> usages = new HtlScanner().scanDirectory(tempDir);

        assertEquals(1, usages.size());
        assertEquals("com.example.core.models.MultiLineModel", usages.get(0).target());
        assertEquals(1, usages.get(0).line());
    }

    @Test
    void testDetectsUnsafeContext(@TempDir Path tempDir) throws IOException {
        String htlContent = """
                <div>
                    <h1>${properties.title}</h1>
                    <div class="content">${properties.richText @ context='unsafe'}</div>
                    <span data-sly-test="${properties.tag @ context="unsafe"}">Tag</span>
                </div>
                """;
        Path htlFile = tempDir.resolve("content.html");
        Files.writeString(htlFile, htlContent);

        HtlScanner scanner = new HtlScanner();
        var result = scanner.scan(tempDir);

        assertEquals(2, result.unsafeContexts().size());
        assertEquals(3, result.unsafeContexts().get(0).line());
        assertTrue(result.unsafeContexts().get(0).snippet().contains("@ context='unsafe'"));
        assertEquals(4, result.unsafeContexts().get(1).line());
    }
}
