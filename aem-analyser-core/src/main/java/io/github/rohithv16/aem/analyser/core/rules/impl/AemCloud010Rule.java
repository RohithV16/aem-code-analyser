package io.github.rohithv16.aem.analyser.core.rules.impl;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.vault.VaultReplicationContentParser;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AEM-CLOUD-010: replication agents, transport configuration and transport handlers that AEM as a
 * Cloud Service ignores.
 *
 * <p>Cloud publishing does not use replication agents: content is distributed through Sling Content
 * Distribution to an Adobe-hosted pipeline service, so an agent under {@code /etc/replication}, an
 * OSGi config tuning {@code com.day.cq.replication.impl.*} or a Sling distribution agent, and a
 * custom {@code TransportHandler} all configure nothing. The symptom is the worst possible one:
 * publishing appears to work, the agent reports idle, and nothing arrives.
 *
 * <p>A custom {@code TransportHandler}, {@code ContentBuilder} or {@code ReplicationPreprocessor}
 * is an ERROR because the code cannot run at all: there is no transport to plug into. Shipped
 * content and OSGi configuration are WARNINGs — inert, not fatal — and both are recognised by their
 * location, so no heuristic decides whether they are "really" used.
 *
 * <p>Not reported: programmatic {@code Replicator} / {@code AgentManager} usage. The replica API
 * still triggers publication through the pipeline service on Cloud, so flagging every call would
 * be a false positive; only configuration and transports that cannot exist are reported here.
 */
public final class AemCloud010Rule implements RuleEvaluator {

    /** Configurations for replication transports/queues; the pipeline service owns the real ones. */
    private static final List<String> REPLICATION_CONFIG_PREFIXES = List.of(
            "com.day.cq.replication.impl.",
            "org.apache.sling.distribution.agent.impl.",
            "org.apache.sling.distribution.transport.");

    /** Interfaces a project can implement only when it owns the transport, which Cloud does not. */
    private static final Set<String> TRANSPORT_INTERFACES = Set.of(
            "TransportHandler", "ContentBuilder", "ReplicationPreprocessor");

    private static final String REPLICATION_PACKAGE = "com.day.cq.replication";

    private static final ParserConfiguration PARSER_CONFIGURATION = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    private final VaultReplicationContentParser contentParser = new VaultReplicationContentParser();

    @Override
    public String getRuleId() {
        return RuleId.AEM_CLOUD_010.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        violations.addAll(contentFindings(context));
        violations.addAll(configFindings(context));
        violations.addAll(transportHandlerFindings(context));
        return violations;
    }

    /** (a) Agent nodes shipped as content under {@code /etc/replication}. */
    private List<Violation> contentFindings(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (Path file : context.fileInventory().contentXml()) {
            String normalized = file.toString().replace('\\', '/');
            if (!normalized.contains("/jcr_root/etc/replication/")) {
                continue;
            }
            var agent = contentParser.read(file);
            String detail = describe(agent);
            violations.add(new Violation(
                    RuleId.AEM_CLOUD_010.id(),
                    RuleId.AEM_CLOUD_010.defaultLevel(),
                    "LEGACY_REPLICATION_CONTENT",
                    "Replication agent content '" + (agent == null ? normalized : agent.nodePath())
                            + "' is shipped under /etc/replication" + detail
                            + "; AEM as a Cloud Service distributes content through the pipeline "
                            + "service and ignores replication agents.",
                    context.relativePath(file),
                    TextRegion.forLine(agent == null ? 1 : agent.line()),
                    agent == null ? null : agent.nodePath(),
                    "Delete the agent from the package. Publish through Cloud Manager / the pipeline "
                            + "service; agent-level tuning has no equivalent on Cloud.",
                    List.of()));
        }
        return violations;
    }

    /** Names the inert properties the node sets, so the report explains why nothing happens. */
    private String describe(VaultReplicationContentParser.ReplicationAgent agent) {
        if (agent == null) {
            return "";
        }
        List<String> properties = new ArrayList<>();
        if (agent.transportUri() != null) {
            properties.add("transportUri=\"" + agent.transportUri() + "\"");
        }
        if (agent.serializedAsXml() != null) {
            properties.add("serializeAsXML=\"" + agent.serializedAsXml() + "\"");
        }
        if (agent.resourceType() != null) {
            properties.add("sling:resourceType=\"" + agent.resourceType() + "\"");
        }
        return properties.isEmpty() ? "" : " (" + String.join(", ", properties) + ")";
    }

    /** (b) OSGi configuration for a replication transport, queue or distribution agent. */
    private List<Violation> configFindings(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();
        for (OSGiConfigNode config : context.configs()) {
            String pid = config.getPid();
            if (pid == null || !targetsReplication(pid)) {
                continue;
            }
            Path file = config.getFilePath();
            String key = pid + "@" + file;
            if (!reported.add(key)) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_CLOUD_010.id(),
                    RuleId.AEM_CLOUD_010.defaultLevel(),
                    "REPLICATION_OSGI_CONFIG",
                    "OSGi configuration for '" + pid + "' tunes a replication transport, queue or "
                            + "distribution agent. On AEM as a Cloud Service these services are not "
                            + "configurable — the pipeline service owns distribution.",
                    context.relativePath(file),
                    TextRegion.forWholeFile(),
                    pid,
                    "Delete the configuration. If the intent was to publish content, use the "
                            + "replication API or a workflow; transport tuning has no Cloud equivalent.",
                    List.of()));
        }
        return violations;
    }

    private boolean targetsReplication(String pid) {
        for (String prefix : REPLICATION_CONFIG_PREFIXES) {
            if (pid.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** (c) A class that implements an interface only a custom transport can implement. */
    private List<Violation> transportHandlerFindings(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (Path file : context.fileInventory().javaFiles()) {
            if (!mentionsTransportInterface(file)) {
                continue;
            }
            CompilationUnit unit = parse(file);
            if (unit == null || !importsReplication(unit)) {
                continue;
            }
            for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                String matched = matchTransportInterface(type);
                if (matched == null) {
                    continue;
                }
                int line = type.getName().getRange().map(range -> range.begin.line).orElse(1);
                violations.add(new Violation(
                        RuleId.AEM_CLOUD_010.id(),
                        Severity.ERROR,
                        "CUSTOM_TRANSPORT_HANDLER",
                        "Class '" + type.getNameAsString() + "' implements '" + matched
                                + "'. AEM as a Cloud Service never calls a project transport: "
                                + "distribution is handled by the Adobe-hosted pipeline service, so "
                                + "this code is dead on arrival.",
                        context.relativePath(file),
                        new TextRegion(line, 1, line, 2),
                        null,
                        "Remove the custom transport and use the standard replication API, or move the "
                                + "logic into a workflow step or a Sling Distribution package builder "
                                + "that Cloud supports.",
                        List.of()));
            }
        }
        return violations;
    }

    private boolean mentionsTransportInterface(Path file) {
        try {
            String source = Files.readString(file);
            if (!source.contains(REPLICATION_PACKAGE)) {
                return false;
            }
            return TRANSPORT_INTERFACES.stream().anyMatch(source::contains);
        } catch (IOException unreadable) {
            return false;
        }
    }

    private CompilationUnit parse(Path file) {
        try {
            ParseResult<CompilationUnit> result = new JavaParser(PARSER_CONFIGURATION).parse(file);
            return result.getResult().orElse(null);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private boolean importsReplication(CompilationUnit unit) {
        return unit.getImports().stream().anyMatch(imported ->
                imported.getNameAsString().startsWith(REPLICATION_PACKAGE + ".")
                        || imported.getNameAsString().equals(REPLICATION_PACKAGE));
    }

    private String matchTransportInterface(ClassOrInterfaceDeclaration type) {
        List<String> supertypes = new ArrayList<>();
        type.getImplementedTypes().forEach(supertype -> supertypes.add(supertype.getNameAsString()));
        type.getExtendedTypes().forEach(supertype -> supertypes.add(supertype.getNameAsString()));
        for (String supertype : supertypes) {
            String simpleName = supertype.substring(supertype.lastIndexOf('.') + 1);
            if (TRANSPORT_INTERFACES.contains(simpleName)
                    && (supertype.startsWith(REPLICATION_PACKAGE + ".") || !supertype.contains("."))) {
                return supertype;
            }
        }
        return null;
    }
}
