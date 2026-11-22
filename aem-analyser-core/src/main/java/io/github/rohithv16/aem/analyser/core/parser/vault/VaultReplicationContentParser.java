package io.github.rohithv16.aem.analyser.core.parser.vault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a replication agent's {@code .content.xml} node for the properties that only mean something
 * on-premise: the transport URI, the XML serialisation flag and the (usually custom) resource type.
 *
 * <p>These files are JCR content, so they are read as text rather than through a DOM: the analyser
 * only needs the attribute values and the line each one sits on, and a malformed agent node must not
 * fail the run.
 */
public final class VaultReplicationContentParser {

    private static final Pattern ATTRIBUTE =
            Pattern.compile("(?:^|\\s)([A-Za-z0-9_:.\\-]+)=\"([^\"]*)\"");

    private static final String REPLICATION_ROOT = "/jcr_root/etc/replication/";

    /**
     * One replication node; every property is {@code null} when the node does not declare it.
     *
     * @param nodePath     the repository path of the agent, e.g.
     *                     {@code etc/replication/agents.author/publish}
     * @param transportUri {@code transportUri}, when present
     * @param serializedAsXml {@code serializeAsXML}, when present
     * @param resourceType {@code sling:resourceType}, when present
     * @param line         1-based line of the most specific property, else 1
     */
    public record ReplicationAgent(String nodePath, String transportUri, String serializedAsXml,
                                   String resourceType, int line) {
    }

    /** @return the agent node, or {@code null} when {@code file} is not under a jcr_root replication tree */
    public ReplicationAgent read(Path file) {
        String normalized = file.toAbsolutePath().normalize().toString().replace('\\', '/');
        int index = normalized.indexOf(REPLICATION_ROOT);
        if (index < 0) {
            return null;
        }
        String nodePath = normalized.substring(index + REPLICATION_ROOT.length());
        if (nodePath.endsWith("/.content.xml")) {
            nodePath = nodePath.substring(0, nodePath.length() - "/.content.xml".length());
        } else if (nodePath.endsWith(".content.xml")) {
            nodePath = nodePath.substring(0, nodePath.length() - ".content.xml".length());
        }
        String transportUri = null;
        String serializedAsXml = null;
        String resourceType = null;
        int line = 1;
        try {
            var lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = ATTRIBUTE.matcher(lines.get(i));
                while (matcher.find()) {
                    // Legacy agents sometimes carry the cq: prefix on properties; the local name is
                    // what the replication queue reads.
                    String name = matcher.group(1).replaceFirst("^cq:", "");
                    switch (name) {
                        case "transportUri" -> {
                            transportUri = matcher.group(2);
                            line = i + 1;
                        }
                        case "serializeAsXML" -> serializedAsXml = matcher.group(2);
                        case "sling:resourceType" -> resourceType = matcher.group(2);
                        default -> {
                            // Not a property this rule reports on.
                        }
                    }
                }
            }
        } catch (IOException unreadable) {
            // A node we cannot read is still shipped content; the path alone is the finding.
        }
        return new ReplicationAgent(nodePath, transportUri, serializedAsXml, resourceType, line);
    }
}
