package io.github.rohithv16.aem.analyser.core.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsgiConfigScannerTest {

    @TempDir
    Path tempDir;

    private Path createConfig(String moduleRelativePath, String content) throws Exception {
        Path file = tempDir.resolve(moduleRelativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void discoversStrictOsgiconfigAnchorWithRunmodes() throws Exception {
        createConfig("src/main/content/jcr_root/apps/myapp/osgiconfig/config.author.dev/com.example.core.Foo.cfg.json",
                "{}");
        List<OsgiConfigScanner.DiscoveredConfig> configs = new OsgiConfigScanner().scanModule(tempDir);
        assertEquals(1, configs.size());
        assertEquals(Set.of("author", "dev"), configs.get(0).runmodes());
        assertEquals("com.example.core.Foo", configs.get(0).pid());
    }

    @Test
    void discoversLegacyFallbackAnchor() throws Exception {
        createConfig("src/main/content/jcr_root/apps/myapp/config.publish/com.example.core.Bar.config",
                "port=I\"8080\"");
        List<OsgiConfigScanner.DiscoveredConfig> configs = new OsgiConfigScanner().scanModule(tempDir);
        assertEquals(1, configs.size());
        assertEquals(Set.of("publish"), configs.get(0).runmodes());
    }

    @Test
    void rejectsToolingHazardFiles() throws Exception {
        createConfig("lombok.config", "lombok.log.fieldName=LOG");
        createConfig("src/main/content/jcr_root/apps/myapp/osgiconfig/config/.editorconfig", "x=1");
        List<OsgiConfigScanner.DiscoveredConfig> configs = new OsgiConfigScanner().scanModule(tempDir);
        assertTrue(configs.isEmpty());
    }

    @Test
    void rejectsNonOsgiContentXml() throws Exception {
        createConfig("ui.config/src/main/content/jcr_root/apps/myapp/osgiconfig/config/rewriter/.content.xml",
                "<jcr:root jcr:primaryType=\"nt:file\"/>");
        List<OsgiConfigScanner.DiscoveredConfig> configs = new OsgiConfigScanner().scanModule(tempDir);
        assertTrue(configs.isEmpty());
    }

    @Test
    void acceptsSlingOsgiConfigContentXml() throws Exception {
        createConfig("src/main/content/jcr_root/apps/myapp/osgiconfig/config/com.example.core.Baz.content.xml",
                "<jcr:root jcr:primaryType=\"sling:OsgiConfig\" key=\"value\"/>");
        List<OsgiConfigScanner.DiscoveredConfig> configs = new OsgiConfigScanner().scanModule(tempDir);
        assertEquals(1, configs.size());
        assertEquals("com.example.core.Baz", configs.get(0).pid());
    }

    @Test
    void rejectsSingleWordPidGrammar() throws Exception {
        createConfig("src/main/content/jcr_root/apps/myapp/osgiconfig/config/lombok.config", "x=1");
        List<OsgiConfigScanner.DiscoveredConfig> configs = new OsgiConfigScanner().scanModule(tempDir);
        assertTrue(configs.isEmpty());
    }
}
