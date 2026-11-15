package io.github.rohithv16.aem.analyser.core.parser.config;

import io.github.rohithv16.aem.analyser.core.model.graph.ConfigFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Polyglot configuration ingestion facade: dispatches a configuration file to the
 * correct format parser and produces a normalized {@link OsgiConfigDefinition}.
 */
public final class OsgiConfigParser {

    private final CfgJsonConfigParser cfgJsonParser = new CfgJsonConfigParser();
    private final FelixConfigParser felixParser = new FelixConfigParser();
    private final PropertiesConfigParser propertiesParser = new PropertiesConfigParser();
    private final XmlOsgiConfigParser xmlParser = new XmlOsgiConfigParser();

    public OsgiConfigDefinition parse(Path file, Set<String> runmodes) throws IOException {
        String fileName = file.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        boolean isCfgJson = lower.endsWith(".cfg.json");
        ConfigFormat format;
        Map<String, ConfigPropertyEntry> properties;
        if (isCfgJson) {
            format = ConfigFormat.CFG_JSON;
            properties = cfgJsonParser.parse(file);
        } else if (lower.endsWith(".config")) {
            format = ConfigFormat.FELIX_CONFIG;
            properties = felixParser.parse(file);
        } else if (lower.endsWith(".cfg")) {
            format = ConfigFormat.PROPERTIES;
            properties = propertiesParser.parse(file);
        } else if (lower.endsWith(".content.xml")) {
            format = ConfigFormat.CONTENT_XML;
            properties = xmlParser.parse(file);
        } else {
            throw new IOException("Unsupported OSGi configuration file format: " + file);
        }

        String baseName = ConfigFilePidResolver.stripExtension(fileName);
        ConfigFilePidResolver.ResolvedPid resolved = ConfigFilePidResolver.resolve(baseName, isCfgJson);
        return new OsgiConfigDefinition(file.toAbsolutePath().normalize(), resolved.pid(),
                resolved.factorySubname(), resolved.isFactory(), resolved.ambiguousHyphen(),
                runmodes, format, properties);
    }

    public boolean isSupported(Path file) {
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".cfg.json") || lower.endsWith(".config")
                || lower.endsWith(".cfg") || lower.endsWith(".content.xml");
    }
}
