package io.github.rohithv16.aem.analyser.core.parser.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigKeyParserTest {

    @Test
    void stripsRfc724TypeHints() {
        assertEquals("scheduledpurge.daysold", ConfigKeyParser.parseKey("scheduledpurge.daysold:Integer").baseKey());
        assertEquals("Integer", ConfigKeyParser.parseKey("scheduledpurge.daysold:Integer").typeHint());
        assertEquals("props", ConfigKeyParser.parseKey("props:String[]").baseKey());
        assertEquals("String[]", ConfigKeyParser.parseKey("props:String[]").typeHint());
    }

    @Test
    void preservesColonPrefixedKeysUntouched() {
        ConfigKeyParser.ParsedPropertyKey parsed = ConfigKeyParser.parseKey(":org.apache.felix.configadmin.revision");
        assertEquals(":org.apache.felix.configadmin.revision", parsed.baseKey());
        assertNull(parsed.typeHint());
    }

    @Test
    void plainKeysHaveNoTypeHint() {
        ConfigKeyParser.ParsedPropertyKey parsed = ConfigKeyParser.parseKey("bulkReplicationBatchSize");
        assertEquals("bulkReplicationBatchSize", parsed.baseKey());
        assertFalse(parsed.hasTypeHint());
    }

    @Test
    void detectsCloudManagerPlaceholders() {
        assertTrue(ConfigKeyParser.isEnvPlaceholder("$[env:API_URL]"));
        assertTrue(ConfigKeyParser.isEnvPlaceholder("$[secret:API_KEY]"));
        assertTrue(ConfigKeyParser.isEnvPlaceholder("$[env:VAR;default=https://api.example.com:8080]"));
        assertFalse(ConfigKeyParser.isEnvPlaceholder("https://api.example.com"));
    }

    @Test
    void extractsPlaceholderDefaultsContainingColons() {
        assertEquals("https://api.example.com:8080",
                ConfigKeyParser.extractPlaceholderDefault("$[env:API_URL;default=https://api.example.com:8080]"));
        assertEquals("$[env:VAR]", ConfigKeyParser.extractPlaceholderDefault("$[env:VAR]"));
    }
}
