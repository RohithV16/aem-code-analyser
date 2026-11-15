package io.github.aemtools.analyser.core.parser.config;

import io.github.aemtools.analyser.core.model.graph.ConfigFormat;
import io.github.aemtools.analyser.core.model.graph.DataType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Normalized immutable domain record produced by every OSGi configuration parser.
 */
public record OsgiConfigDefinition(
        Path filePath,
        String pid,
        String factoryIdentifier,
        boolean isFactory,
        boolean factoryAmbiguous,
        Set<String> runmodes,
        ConfigFormat format,
        Map<String, ConfigPropertyEntry> properties) {

    public OsgiConfigDefinition {
        runmodes = Set.copyOf(runmodes == null ? Set.of() : runmodes);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public String basePid() {
        return pid;
    }

    public List<ConfigPropertyEntry> propertyList() {
        return List.copyOf(properties.values());
    }
}
