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
}
