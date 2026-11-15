package io.github.rohithv16.aem.analyser.core.parser.config;

import io.github.rohithv16.aem.analyser.core.model.graph.DataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CfgJsonConfigParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesTypedKeysWithExactLocations() throws Exception {
        Path file = tempDir.resolve("com.example.Config.cfg.json");
        Files.writeString(file, """
                {
                  // comments are tolerated
                  "enabled:Integer": 1,
                  "obsoleteEndpoint": "https://old.example.com",
                  "props:String[]": ["a", "b"],
                  "flag:Boolean": true
                }
                """);

        Map<String, ConfigPropertyEntry> properties = new CfgJsonConfigParser().parse(file);
        assertEquals(4, properties.size());

        ConfigPropertyEntry enabled = properties.get("enabled");
        assertEquals("enabled:Integer", enabled.rawKey());
        assertEquals("Integer", enabled.typeHint());
        assertEquals(DataType.INTEGER, enabled.inferredType());
        assertEquals("1", enabled.rawValue());
        assertEquals(3, enabled.line());

        ConfigPropertyEntry obsolete = properties.get("obsoleteEndpoint");
        assertEquals(DataType.STRING, obsolete.inferredType());
        assertFalse(obsolete.isEnvPlaceholder());
        assertEquals(4, obsolete.line());

        ConfigPropertyEntry props = properties.get("props");
        assertEquals(DataType.ARRAY, props.inferredType());
        assertTrue(props.rawValue().contains("a"));

        ConfigPropertyEntry flag = properties.get("flag");
        assertEquals(DataType.BOOLEAN, flag.inferredType());
    }

    @Test
    void detectsEnvPlaceholders() throws Exception {
        Path file = tempDir.resolve("com.example.Env.cfg.json");
        Files.writeString(file, """
                {
                  "apiKey": "$[secret:API_KEY]",
                  "apiUrl": "$[env:API_URL;default=https://api.example.com:8080]"
                }
                """);
        Map<String, ConfigPropertyEntry> properties = new CfgJsonConfigParser().parse(file);
        assertTrue(properties.get("apiKey").isEnvPlaceholder());
        assertTrue(properties.get("apiUrl").isEnvPlaceholder());
    }

    @Test
    void toleratesTrailingCommasAndBlockComments() throws Exception {
        Path file = tempDir.resolve("com.example.Tolerant.cfg.json");
        Files.writeString(file, """
                {
                  "a": 1,
                  /* block comment */
                  "b": 2,
                }
                """);
        Map<String, ConfigPropertyEntry> properties = new CfgJsonConfigParser().parse(file);
        assertEquals(2, properties.size());
        List<String> keys = properties.values().stream().map(ConfigPropertyEntry::baseKey).toList();
        assertTrue(keys.contains("a"));
        assertTrue(keys.contains("b"));
    }
}
