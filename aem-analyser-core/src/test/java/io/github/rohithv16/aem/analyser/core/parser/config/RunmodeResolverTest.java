package io.github.rohithv16.aem.analyser.core.parser.config;

import io.github.rohithv16.aem.analyser.core.model.graph.ConfigFormat;
import io.github.rohithv16.aem.analyser.core.model.graph.DataType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RunmodeResolverTest {

    private final Path root = Path.of("/mock");

    private OsgiConfigDefinition config(String pid, Set<String> runmodes, Map<String, String> properties) {
        Map<String, ConfigPropertyEntry> entries = new java.util.LinkedHashMap<>();
        properties.forEach((key, value) -> entries.put(key, new ConfigPropertyEntry(
                key, key, null, value, DataType.STRING, false, 1, 1)));
        return new OsgiConfigDefinition(root.resolve(pid + ".cfg.json"), pid, null, false, false,
                runmodes, ConfigFormat.CFG_JSON, entries);
    }

    @Test
    void moreSpecificRunmodeOverridesBaseAndUnrelatedRunmodeIsIgnored() {
        List<OsgiConfigDefinition> configs = List.of(
                config("com.example.Service", Set.of(), Map.of("a", "base", "b", "base")),
                config("com.example.Service", Set.of("author"), Map.of("a", "author")),
                config("com.example.Service", Set.of("author", "dev"), Map.of("c", "dev")),
                config("com.example.Service", Set.of("publish"), Map.of("a", "publish")));

        Map<String, String> effective = RunmodeResolver.effectiveProperties(
                "com.example.Service", configs, Set.of("author", "dev"));

        assertEquals("author", effective.get("a"));
        assertEquals("base", effective.get("b"));
        assertEquals("dev", effective.get("c"));
        assertFalse(effective.containsValue("publish"));
    }

    @Test
    void baseConfigAppliesWhenNoRunmodesAreActive() {
        List<OsgiConfigDefinition> configs = List.of(
                config("com.example.Service", Set.of(), Map.of("a", "base")),
                config("com.example.Service", Set.of("author"), Map.of("a", "author")));

        Map<String, String> effective = RunmodeResolver.effectiveProperties(
                "com.example.Service", configs, Set.of());

        assertEquals("base", effective.get("a"));
    }
}
