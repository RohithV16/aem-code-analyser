package io.github.rohithv16.aem.analyser.core.parser.config;

import io.github.rohithv16.aem.analyser.core.model.graph.DataType;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonReadFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 724 (.cfg.json) streaming parser built on Jackson with exact token
 * line/column tracking, comment and trailing-comma tolerance, typed key stripping
 * and Cloud Manager placeholder detection.
 */
public final class CfgJsonConfigParser {

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .build();

    /**
     * Parses the root JSON object of a .cfg.json file.
     *
     * @return property entries keyed by semantic base key, with exact locations
     * @throws IOException on malformed JSON
     */
    public Map<String, ConfigPropertyEntry> parse(Path file) throws IOException {
        Map<String, ConfigPropertyEntry> properties = new LinkedHashMap<>();
        try (JsonParser parser = JSON_FACTORY.createParser(Files.newBufferedReader(file))) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Expected JSON object at root of " + file);
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                JsonLocation keyLocation = parser.getTokenLocation();
                String rawKey = parser.currentName();
                parser.nextToken();
                ConfigPropertyEntry entry = readProperty(parser, keyLocation, rawKey);
                if (entry != null) {
                    properties.put(entry.baseKey(), entry);
                }
            }
        }
        return properties;
    }

    private ConfigPropertyEntry readProperty(JsonParser parser, JsonLocation keyLocation, String rawKey)
            throws IOException {
        // Keys starting with ':' are RFC 724 comments / framework metadata: preserved untouched
        ConfigKeyParser.ParsedPropertyKey parsed = ConfigKeyParser.parseKey(rawKey);
        JsonToken valueToken = parser.currentToken();

        return switch (valueToken) {
            case START_ARRAY -> {
                String raw = readRawArrayOrObject(parser);
                yield new ConfigPropertyEntry(parsed.baseKey(), rawKey, parsed.typeHint(),
                        raw, DataType.ARRAY, false,
                        keyLocation.getLineNr(), keyLocation.getColumnNr());
            }
            case START_OBJECT -> {
                String raw = readRawArrayOrObject(parser);
                yield new ConfigPropertyEntry(parsed.baseKey(), rawKey, parsed.typeHint(),
                        raw, DataType.OBJECT, false,
                        keyLocation.getLineNr(), keyLocation.getColumnNr());
            }
            case VALUE_STRING -> {
                String value = parser.getValueAsString();
                boolean envPlaceholder = ConfigKeyParser.isEnvPlaceholder(value);
                yield new ConfigPropertyEntry(parsed.baseKey(), rawKey, parsed.typeHint(),
                        value, DataType.STRING, envPlaceholder,
                        keyLocation.getLineNr(), keyLocation.getColumnNr());
            }
            case VALUE_NUMBER_INT -> new ConfigPropertyEntry(parsed.baseKey(), rawKey, parsed.typeHint(),
                    parser.getValueAsString(), DataType.INTEGER, false,
                    keyLocation.getLineNr(), keyLocation.getColumnNr());
            case VALUE_NUMBER_FLOAT -> new ConfigPropertyEntry(parsed.baseKey(), rawKey, parsed.typeHint(),
                    parser.getValueAsString(), DataType.DOUBLE, false,
                    keyLocation.getLineNr(), keyLocation.getColumnNr());
            case VALUE_TRUE, VALUE_FALSE -> new ConfigPropertyEntry(parsed.baseKey(), rawKey, parsed.typeHint(),
                    String.valueOf(parser.getBooleanValue()), DataType.BOOLEAN, false,
                    keyLocation.getLineNr(), keyLocation.getColumnNr());
            case VALUE_NULL -> null;
            default -> null;
        };
    }

    private String readRawArrayOrObject(JsonParser parser) throws IOException {
        // The parser is positioned ON the START_ARRAY / START_OBJECT token
        JsonToken current = parser.currentToken();
        boolean isArray = current == JsonToken.START_ARRAY;
        StringBuilder sb = new StringBuilder(isArray ? "[" : "{");
        int depth = 1;
        boolean first = true;
        while (depth > 0) {
            JsonToken token = parser.nextToken();
            if (token == null) {
                break;
            }
            switch (token) {
                case START_ARRAY -> {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append('[');
                    depth++;
                }
                case START_OBJECT -> {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append('{');
                    depth++;
                }
                case END_ARRAY -> {
                    sb.append(']');
                    depth--;
                }
                case END_OBJECT -> {
                    sb.append('}');
                    depth--;
                }
                default -> {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append(switch (token) {
                        case VALUE_STRING -> '"' + parser.getValueAsString().replace("\"", "\\\"") + '"';
                        case VALUE_TRUE, VALUE_FALSE -> String.valueOf(parser.getBooleanValue());
                        case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> parser.getValueAsString();
                        case VALUE_NULL -> "null";
                        case VALUE_EMBEDDED_OBJECT -> '"' + parser.getValueAsString() + '"';
                        default -> "null";
                    });
                }
            }
            first = false;
        }
        return sb.toString();
    }

    /**
     * Value type inference helper shared by the legacy parsers.
     */
    public static List<String> normalizeValueList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(item -> result.add(String.valueOf(item)));
        } else if (value != null) {
            result.add(String.valueOf(value));
        }
        return result;
    }
}
