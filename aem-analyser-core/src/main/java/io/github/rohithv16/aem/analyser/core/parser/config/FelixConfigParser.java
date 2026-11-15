package io.github.rohithv16.aem.analyser.core.parser.config;

import io.github.rohithv16.aem.analyser.core.model.graph.DataType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Legacy Apache Felix ConfigurationAdmin (.config) parser.
 * Handles typed property syntax ({@code I"8080"}, {@code L"123456789"}, {@code B"true"},
 * {@code ["a","b"]} arrays), escape sequences, comment lines and backslash line continuations.
 */
public final class FelixConfigParser {

    public Map<String, ConfigPropertyEntry> parse(Path file) throws IOException {
        String content = Files.readString(file);
        // Normalize line continuations: backslash followed by newline
        String normalized = content.replace("\\\r\n", "").replace("\\\n", "").replace("\\\r", "");

        Map<String, ConfigPropertyEntry> properties = new LinkedHashMap<>();
        String[] lines = normalized.split("\r\n|\n|\r", -1);
        int lineOffset = 0;
        for (String line : lines) {
            lineOffset++;
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = indexOfTopLevelEquals(trimmed);
            if (eq <= 0) {
                continue;
            }
            String rawKey = trimmed.substring(0, eq).strip();
            String valueText = trimmed.substring(eq + 1).strip();
            int column = line.indexOf(rawKey) + 1;
            ConfigKeyParser.ParsedPropertyKey parsedKey = ConfigKeyParser.parseKey(rawKey);

            ParsedValue value = parseValue(valueText, 0);
            properties.put(parsedKey.baseKey(), new ConfigPropertyEntry(
                    parsedKey.baseKey(), rawKey, parsedKey.typeHint(),
                    value.text(), value.type(), ConfigKeyParser.isEnvPlaceholder(value.text()),
                    lineOffset, column));
        }
        return properties;
    }

    private int indexOfTopLevelEquals(String line) {
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (c == '=' && !inString) {
                return i;
            }
        }
        return -1;
    }

    record ParsedValue(String text, DataType type, int endIndex) {
    }

    private ParsedValue parseValue(String text, int start) {
        if (start >= text.length()) {
            return new ParsedValue("", DataType.STRING, text.length());
        }
        if (text.charAt(start) == '[') {
            return parseArray(text, start);
        }
        return parseScalar(text, start, false);
    }

    private ParsedValue parseArray(String text, int start) {
        List<String> elements = new ArrayList<>();
        DataType elementType = DataType.STRING;
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                i++;
                continue;
            }
            if (c == ']') {
                i++;
                break;
            }
            ParsedValue element = parseScalar(text, i, true);
            elements.add(element.text());
            elementType = widen(elementType, element.type());
            i = element.endIndex();
        }
        // A single plain quoted string (no explicit type code) is a very common
        // hand-written way of wrapping one value, e.g. user.mapping=["ocs\=..."]
        if (elements.size() == 1 && elementType == DataType.STRING) {
            return new ParsedValue(elements.get(0), DataType.STRING, i);
        }
        return new ParsedValue("[" + String.join(", ", elements) + "]", DataType.ARRAY, i);
    }

    private ParsedValue parseScalar(String text, int start, boolean inArray) {
        int i = start;
        DataType type = DataType.STRING;
        // optional leading type code directly followed by a quote
        if (i + 1 < text.length() && text.charAt(i + 1) == '"'
                && "ILFDBSXT".indexOf(text.charAt(i)) >= 0) {
            type = typeFromCode(text.charAt(i));
            i += 1;
        }
        if (i < text.length() && text.charAt(i) == '"') {
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    sb.append(switch (next) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '\\' -> '\\';
                        case '"' -> '"';
                        default -> next;
                    });
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    i++;
                    break;
                }
                sb.append(c);
                i++;
            }
            return new ParsedValue(sb.toString(), type, i);
        }
        // bare token
        StringBuilder sb = new StringBuilder();
        while (i < text.length()
                && !Character.isWhitespace(text.charAt(i))
                && text.charAt(i) != ','
                && (!inArray || text.charAt(i) != ']')) {
            sb.append(text.charAt(i));
            i++;
        }
        String token = sb.toString();
        if (type == DataType.STRING) {
            type = inferBareToken(token);
        }
        return new ParsedValue(token, type, i);
    }

    private DataType inferBareToken(String token) {
        if (token.equalsIgnoreCase("true") || token.equalsIgnoreCase("false")) {
            return DataType.BOOLEAN;
        }
        try {
            Integer.parseInt(token);
            return DataType.INTEGER;
        } catch (NumberFormatException e) {
            // not an integer
        }
        try {
            Double.parseDouble(token);
            return DataType.DOUBLE;
        } catch (NumberFormatException e) {
            // not a double
        }
        return DataType.STRING;
    }

    private DataType typeFromCode(char code) {
        return switch (code) {
            case 'I' -> DataType.INTEGER;
            case 'L' -> DataType.LONG;
            case 'F' -> DataType.FLOAT;
            case 'D' -> DataType.DOUBLE;
            case 'B' -> DataType.BOOLEAN;
            case 'S' -> DataType.STRING;
            case 'X', 'T' -> DataType.STRING;
            default -> DataType.STRING;
        };
    }

    private DataType widen(DataType a, DataType b) {
        if (a == DataType.ARRAY || a == b) {
            return a == DataType.ARRAY ? a : b;
        }
        return DataType.ARRAY;
    }
}
