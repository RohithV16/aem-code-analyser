package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.htl.CqComponentDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JcrComponentScannerTest {

    @Test
    void testDiscoversComponentsAndClassicDialogs(@TempDir Path tempDir) throws IOException {
        Path compDir = tempDir.resolve("jcr_root/apps/myapp/components/hero");
        Files.createDirectories(compDir);

        String contentXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jcr:root xmlns:cq="http://www.day.com/jcr/cq/1.0" xmlns:jcr="http://www.jcp.org/jcr/1.0"
                    jcr:primaryType="cq:Component"
                    jcr:title="Hero Component"
                    componentGroup="MyApp - Content"
                    sling:resourceSuperType="core/wcm/components/hero/v1/hero"/>
                """;
        Files.writeString(compDir.resolve(".content.xml"), contentXml);

        // Add a legacy dialog.xml
        Files.writeString(compDir.resolve("dialog.xml"), "<classic-dialog/>");

        JcrComponentScanner scanner = new JcrComponentScanner();
        List<CqComponentDefinition> comps = scanner.scanDirectory(tempDir);

        assertEquals(1, comps.size());
        CqComponentDefinition comp = comps.get(0);
        assertEquals("myapp/components/hero", comp.resourceType());
        assertEquals("core/wcm/components/hero/v1/hero", comp.resourceSuperType());
        assertEquals("Hero Component", comp.title());
        assertEquals("MyApp - Content", comp.componentGroup());
        assertTrue(comp.hasClassicUiDialog());
        assertNotNull(comp.classicUiDialogXml());
    }
}
