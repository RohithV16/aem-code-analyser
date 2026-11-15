package io.github.aemtools.analyser.core.model.graph.node;

import io.github.aemtools.analyser.core.model.graph.ConfigFormat;
import io.github.aemtools.analyser.core.model.graph.NodeType;
import io.github.aemtools.analyser.core.model.graph.SourceLocation;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An OSGi configuration file node (.cfg.json, .config, .cfg or sling:OsgiConfig .content.xml).
 */
public final class OSGiConfigNode extends AbstractPolyglotNode {

    private final String pid;
    private final String factorySubname;
    private final boolean isFactory;
    private final Set<String> runmodes;
    private final ConfigFormat format;
    private final Map<String, ConfigPropertyNode> properties;

    public OSGiConfigNode(String id,
                          Path filePath,
                          SourceLocation sourceLocation,
                          String pid,
                          String factorySubname,
                          boolean isFactory,
                          Set<String> runmodes,
                          ConfigFormat format) {
        super(id, filePath, sourceLocation);
        this.pid = pid;
        this.factorySubname = factorySubname;
        this.isFactory = isFactory;
        this.runmodes = Set.copyOf(runmodes);
        this.format = format;
        this.properties = new LinkedHashMap<>();
    }

    @Override
    public NodeType getNodeType() {
        return NodeType.OSGI_CONFIG;
    }

    public String getPid() {
        return pid;
    }

    public String getFactorySubname() {
        return factorySubname;
    }

    public boolean isFactory() {
        return isFactory;
    }

    public Set<String> getRunmodes() {
        return runmodes;
    }

    public ConfigFormat getFormat() {
        return format;
    }

    public Map<String, ConfigPropertyNode> getProperties() {
        return properties;
    }

    public void addProperty(ConfigPropertyNode property) {
        properties.put(property.getKey().toLowerCase(Locale.ROOT), property);
    }

    public ConfigPropertyNode getProperty(String key) {
        return properties.get(key.toLowerCase(Locale.ROOT));
    }
}
