package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.ConfigFormat;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud010Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AEM-CLOUD-010 against the cases from the issue: an agent node with a transport URI plus a class
 * implementing {@code com.day.cq.replication.TransportHandler} (one warning, one error), an OSGi
 * config for a replication transport, and the negatives that pin precision — a build-output copy of
 * the same fixture, content elsewhere in the package, and a class that merely shares an interface
 * name without the replication package.
 */
class AemCloud010RuleTest {

    private static final String AGENT = "ui.apps/src/main/content/jcr_root/etc/replication/"
            + "agents.author/zzprobe/.content.xml";

    @TempDir
    Path root;

    @Test
    void agentContentAndCustomTransportHandlerAreReported() throws Exception {
        file(AGENT, """
                <jcr:root xmlns:cq="http://www.day.com/jcr/cq/1.0"
                          jcr:primaryType="cq:Page">
                    <jcr:content
                        transportUri="http://localhost:4503/bin/receive?service=com.day.cq.replication.impl.transport.HttpTransportHandle"
                        serializeAsXML="true"
                        sling:resourceType="cq/replication/components/agent"/>
                </jcr:root>
                """);
        file("core/src/main/java/com/acme/LegacyTransport.java", """
                package com.acme;

                import com.day.cq.replication.TransportHandler;

                public class LegacyTransport implements TransportHandler {
                    public void deliver() {
                    }
                }
                """);

        List<Violation> violations = evaluate(List.of());

        assertEquals(2, violations.size(), () -> violations.toString());
        assertEquals(Set.of("LEGACY_REPLICATION_CONTENT", "CUSTOM_TRANSPORT_HANDLER"),
                violations.stream().map(Violation::subtype).collect(Collectors.toSet()));
        Violation content = bySubtype(violations, "LEGACY_REPLICATION_CONTENT");
        assertEquals(Severity.WARNING, content.severity());
        assertTrue(content.message().contains("agents.author/zzprobe"), content.message());
        assertTrue(content.message().contains("transportUri"), content.message());
        assertEquals(4, content.region().startLine(), "the finding points at the transportUri attribute");
        Violation handler = bySubtype(violations, "CUSTOM_TRANSPORT_HANDLER");
        assertEquals(Severity.ERROR, handler.severity());
        assertTrue(handler.filePath().endsWith("LegacyTransport.java"), handler.filePath());
    }

    @Test
    void replicationTransportConfigurationIsReported() throws Exception {
        OSGiConfigNode http = config("com.day.cq.replication.impl.transport.HttpTransportHandler",
                "ui.config/src/main/content/jcr_root/apps/acme/osgiconfig/config/"
                        + "com.day.cq.replication.impl.transport.HttpTransportHandler.cfg.json");
        OSGiConfigNode agent = config("org.apache.sling.distribution.agent.impl.PublishDistributionAgentFactory",
                "ui.config/src/main/content/jcr_root/apps/acme/osgiconfig/config/"
                        + "org.apache.sling.distribution.agent.impl.PublishDistributionAgentFactory.cfg.json");
        OSGiConfigNode unrelated = config("org.apache.sling.distribution.communication.QueueConfiguration",
                "ui.config/src/main/content/jcr_root/apps/acme/osgiconfig/config/"
                        + "org.apache.sling.distribution.communication.QueueConfiguration.cfg.json");

        List<Violation> violations = evaluate(List.of(http, agent, unrelated));

        assertEquals(2, violations.size(), () -> "Only the transport and the agent are inert: " + violations);
        assertTrue(violations.stream().allMatch(v -> v.subtype().equals("REPLICATION_OSGI_CONFIG")));
        assertTrue(violations.stream().anyMatch(v -> v.targetPid().endsWith("HttpTransportHandler")));
    }

    @Test
    void buildOutputCopiesAreNotShipped() throws Exception {
        file("ui.apps/target/classes/" + AGENT, """
                <jcr:root jcr:primaryType="cq:Page">
                    <jcr:content cq:transportUri="http://localhost:4503/bin/receive"/>
                </jcr:root>
                """);

        assertTrue(evaluate(List.of()).isEmpty(),
                "target/ is build output; the inventory prunes it, so the same agent copied there is not shipped");
    }

    @Test
    void otherContentUnderThePackageIsSilent() throws Exception {
        file("ui.apps/src/main/content/jcr_root/conf/acme/settings/.content.xml", """
                <jcr:root jcr:primaryType="sling:Folder"/>
                """);
        file("ui.apps/src/main/content/jcr_root/etc/designs/acme/.content.xml", """
                <jcr:root jcr:primaryType="cq:Page"/>
                """);

        assertTrue(evaluate(List.of()).isEmpty());
    }

    @Test
    void classWithTheSameInterfaceNameOutsideTheReplicationPackageIsSilent() throws Exception {
        file("core/src/main/java/com/acme/OwnTransportHandler.java", """
                package com.acme;

                public class OwnTransportHandler implements TransportHandler {
                    public void deliver() {
                    }
                }
                """);
        // Imports the package but implements an unrelated interface.
        file("core/src/main/java/com/acme/ReplicationListener.java", """
                package com.acme;

                import com.day.cq.replication.ReplicationAction;

                public class ReplicationListener implements EventListener {
                    public void onEvent(ReplicationAction action) {
                    }
                }
                """);

        assertTrue(evaluate(List.of()).isEmpty(),
                "The rule needs both the replication package and a transport interface");
    }

    private Violation bySubtype(List<Violation> violations, String subtype) {
        return violations.stream().filter(v -> v.subtype().equals(subtype)).findFirst().orElseThrow();
    }

    private OSGiConfigNode config(String pid, String relativePath) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}");
        return new OSGiConfigNode(file.toString(), file, null, pid, null, false, Set.of(), ConfigFormat.CFG_JSON);
    }

    private void file(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Violation> evaluate(List<OSGiConfigNode> configs) {
        Map<String, OSGiConfigNode> byId = new LinkedHashMap<>();
        configs.forEach(config -> byId.put(config.getId(), config));
        ReferenceGraph graph = new ReferenceGraph();
        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), byId, Collections.emptyMap(),
                Collections.emptyMap(), List.of(), List.of(), null);
        RuleContext context = new RuleContext(graph, buildResult, Collections.emptySet(), null,
                Collections.emptySet(), root);
        return new AemCloud010Rule().evaluate(context);
    }
}
