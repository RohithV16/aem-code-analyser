package io.github.rohithv16.aem.analyser.core.parser.config;

import io.github.rohithv16.aem.analyser.core.model.graph.DataType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code sling:OsgiConfig} {@code .content.xml} nodes, treating each XML
 * attribute (except {@code jcr:primaryType}) as a configuration property.
 */
public final class XmlOsgiConfigParser {

    private static final Pattern ATTRIBUTE_PATTERN =
            Pattern.compile("([a-zA-Z0-9_:.\\-]+)=\"([^\"]*)\"");

    public Map<String, ConfigPropertyEntry> parse(Path file) throws IOException {
        Map<String, ConfigPropertyEntry> properties = new LinkedHashMap<>();
        String content = Files.readString(file);
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(content);
        while (matcher.find()) {
            String rawKey = matcher.group(1);
            if ("jcr:primaryType".equals(rawKey)) {
                continue;
            }
            String value = matcher.group(2);
            ConfigKeyParser.ParsedPropertyKey parsed = ConfigKeyParser.parseKey(rawKey);
            properties.put(parsed.baseKey(), new ConfigPropertyEntry(
                    parsed.baseKey(), rawKey, parsed.typeHint(), value,
                    DataType.STRING, ConfigKeyParser.isEnvPlaceholder(value), 1, 1));
        }
        return properties;
    }
}
