package io.github.aemtools.analyser.core.parser.config;

import io.github.aemtools.analyser.core.model.graph.DataType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standard Java properties parser for legacy {@code .cfg} files.
 */
public final class PropertiesConfigParser {

    public Map<String, ConfigPropertyEntry> parse(Path file) throws IOException {
        Map<String, ConfigPropertyEntry> properties = new LinkedHashMap<>();
        var props = new java.util.Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        }
        for (String rawKey : props.stringPropertyNames()) {
            String value = props.getProperty(rawKey);
            ConfigKeyParser.ParsedPropertyKey parsed = ConfigKeyParser.parseKey(rawKey);
            DataType type = inferType(value);
            properties.put(parsed.baseKey(), new ConfigPropertyEntry(
                    parsed.baseKey(), rawKey, parsed.typeHint(), value, type,
                    ConfigKeyParser.isEnvPlaceholder(value), 1, 1));
        }
        return properties;
    }

    private DataType inferType(String value) {
        if (ConfigKeyParser.isEnvPlaceholder(value)) {
            return DataType.STRING;
        }
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return DataType.BOOLEAN;
        }
        try {
            Integer.parseInt(value);
            return DataType.INTEGER;
        } catch (NumberFormatException e) {
            try {
                Double.parseDouble(value);
                return DataType.DOUBLE;
            } catch (NumberFormatException ignored) {
                return DataType.STRING;
            }
        }
    }
}
