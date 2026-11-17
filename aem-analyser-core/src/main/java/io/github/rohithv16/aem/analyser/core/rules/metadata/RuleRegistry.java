package io.github.rohithv16.aem.analyser.core.rules.metadata;

import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry for rule metadata. Exporters and the CLI must read from here instead of keeping
 * local switch statements so SARIF, Sonar, HTML and {@code --list-rules} cannot drift.
 */
public final class RuleRegistry {

    /**
     * The published issue reference. Anchors are the lower-cased rule id, so SARIF consumers and the
     * HTML dashboard land on the same explanation the site shows.
     */
    private static final String HELP_BASE =
            "https://rohithv16.github.io/aem-code-analyser/issues/#";

    private static final Map<String, RuleDescriptor> BY_ID = build();

    private RuleRegistry() {
    }

    public static RuleDescriptor get(String id) {
        if (id == null || id.isBlank()) {
            return unknown("UNKNOWN");
        }
        RuleDescriptor descriptor = BY_ID.get(id);
        if (descriptor != null) {
            return descriptor;
        }
        String normalized = id.toUpperCase(java.util.Locale.ROOT);
        descriptor = BY_ID.get(normalized);
        return descriptor != null ? descriptor : unknown(id);
    }

    public static RuleDescriptor forRule(RuleId rule) {
        return BY_ID.get(rule.id());
    }

    /**
     * @return the descriptor for a known rule id (case-insensitive), empty for unknown ids
     */
    public static java.util.Optional<RuleDescriptor> find(String id) {
        if (id == null || id.isBlank()) {
            return java.util.Optional.empty();
        }
        RuleDescriptor descriptor = BY_ID.get(id);
        if (descriptor == null) {
            descriptor = BY_ID.get(id.toUpperCase(java.util.Locale.ROOT));
        }
        return java.util.Optional.ofNullable(descriptor);
    }

    public static List<RuleDescriptor> all() {
        return List.copyOf(BY_ID.values());
    }

    private static Map<String, RuleDescriptor> build() {
        Map<String, RuleDescriptor> registry = new LinkedHashMap<>();
        for (RuleId rule : RuleId.values()) {
            registry.put(rule.id(), descriptor(rule));
        }
        // Unmodifiable, but with a stable iteration order: Map.copyOf randomises iteration
        // order per JVM, which made the SARIF rule catalogue and every ruleIndex differ
        // between otherwise identical runs.
        return java.util.Collections.unmodifiableMap(registry);
    }

    private static RuleDescriptor descriptor(RuleId rule) {
        RuleType type = switch (rule) {
            case AEM_DEAD_001, AEM_DEAD_004, AEM_DUP_001, AEM_QUAL_002, AEM_QUAL_004,
                 AEM_PERF_001, AEM_PERF_002, AEM_DEAD_003A -> RuleType.BUG;
            case AEM_QUAL_005, AEM_CLOUD_001, AEM_SEC_001 -> RuleType.VULNERABILITY;
            case AEM_QUAL_003 -> RuleType.SECURITY_HOTSPOT;
            default -> RuleType.CODE_SMELL;
        };
        int effort = switch (rule) {
            case AEM_DEAD_001 -> 15;
            case AEM_DEAD_002A, AEM_DEAD_002B, AEM_DEAD_003C -> 10;
            case AEM_DEAD_003A, AEM_DUP_010 -> 30;
            case AEM_DEAD_003B -> 5;
            case AEM_DEAD_004, AEM_DUP_002, AEM_DUP_003, AEM_QUAL_001, AEM_CLOUD_001 -> 15;
            case AEM_COMP_001, AEM_DUP_001, AEM_QUAL_003, AEM_QUAL_004, AEM_SEC_001 -> 20;
            case AEM_QUAL_002, AEM_PERF_001, AEM_PERF_002, AEM_CLOUD_002 -> 30;
            case AEM_QUAL_005, AEM_PERF_003, AEM_CLOUD_003 -> 25;
            case AEM_CLOUD_004 -> 20;
            case AEM_CLOUD_005 -> 5;
            case AEM_QUAL_006 -> 15;
            case AEM_DEAD_005 -> 10;
            case AEM_SEC_002 -> 25;
            case AEM_PERF_004 -> 20;
        };
        String cwe = switch (rule) {
            case AEM_SEC_001 -> "CWE-79";
            case AEM_CLOUD_001, AEM_QUAL_005 -> "CWE-798";
            case AEM_PERF_003 -> "CWE-400";
            default -> null;
        };
        List<String> tags = switch (rule) {
            case AEM_DEAD_001, AEM_DEAD_002A, AEM_DEAD_002B, AEM_DEAD_003A, AEM_DEAD_003B,
                 AEM_DEAD_003C -> List.of("dead-code", "osgi");
            case AEM_DEAD_004 -> List.of("dead-code", "htl");
            case AEM_DUP_001, AEM_DUP_002, AEM_DUP_003, AEM_DUP_010 -> List.of("duplication");
            case AEM_QUAL_001, AEM_QUAL_002, AEM_QUAL_003, AEM_QUAL_004, AEM_QUAL_005 ->
                    List.of("quality", "aem-cloud");
            case AEM_PERF_001, AEM_PERF_002, AEM_PERF_003 -> List.of("performance");
            case AEM_CLOUD_001, AEM_CLOUD_002, AEM_CLOUD_003 -> List.of("aem-cloud");
            case AEM_CLOUD_004, AEM_CLOUD_005 -> List.of("aem-cloud", "api");
            case AEM_SEC_001 -> List.of("security", "xss");
            case AEM_QUAL_006 -> List.of("quality", "resource-lifecycle");
            case AEM_DEAD_005 -> List.of("dead-code", "clientlib");
            case AEM_SEC_002 -> List.of("security", "ssrf");
            case AEM_PERF_004 -> List.of("performance", "query");
            case AEM_COMP_001 -> List.of("quality", "classic-ui");
        };
        return new RuleDescriptor(
                rule.id(),
                rule.ruleName(),
                rule.defaultLevel(),
                rule.shortDescription(),
                rule.shortDescription(),
                HELP_BASE + rule.id().toLowerCase(java.util.Locale.ROOT),
                tags,
                remediation(rule),
                cwe,
                type,
                effort);
    }

    private static String remediation(RuleId rule) {
        return switch (rule) {
            case AEM_DEAD_001 -> "Delete the orphaned config, or rename its file PID to match the component.";
            case AEM_DEAD_002A -> "Remove the property, fix its spelling, or declare a matching @AttributeDefinition.";
            case AEM_DEAD_002B -> "Consume the attribute in code or delete it from the OCD.";
            case AEM_DEAD_003A -> "Inject the service, delete it, or export its interface package.";
            case AEM_DEAD_003B -> "Bind the model to a resourceType, adapt it somewhere, or delete it.";
            case AEM_DEAD_003C -> "Set immediate = true, provide a service, or delete the component.";
            case AEM_DEAD_004 -> "Correct the class name in the HTL data-sly-use expression.";
            case AEM_DUP_001 -> "Merge the servlets or add distinct selectors/resource types.";
            case AEM_DUP_002 -> "Keep one config per PID and runmode; remove the duplicate.";
            case AEM_DUP_003 -> "Differentiate the resourceType/adapters or merge the models.";
            case AEM_DUP_010 -> "Extract the duplicated block into a shared helper.";
            case AEM_QUAL_001 -> "Register the servlet by resourceType instead of paths (CQBP-75).";
            case AEM_QUAL_002 -> "Implement only consumer-facing APIs; do not implement @ProviderType (CQBP-84).";
            case AEM_QUAL_003 -> "Use Sling Scheduled Jobs instead of the whiteboard scheduler (AMSCORE-554).";
            case AEM_QUAL_004 -> "Do not store session-bound or non-thread-safe objects in component fields.";
            case AEM_QUAL_005 -> "Use a dedicated service user with a mapped subservice (AMSCORE-553).";
            case AEM_COMP_001 -> "Migrate dialog.xml to a Granite UI _cq_dialog.";
            case AEM_PERF_001 -> "Wrap the resolver in try-with-resources.";
            case AEM_PERF_002 -> "Close the owning resolver or call session.logout() in a finally block.";
            case AEM_PERF_003 -> "Add a path or nodetype restriction to the query.";
            case AEM_CLOUD_001 -> "Move the secret to a Cloud Manager secret or $[secret:...] placeholder.";
            case AEM_CLOUD_002 -> "Migrate the JSP to HTL.";
            case AEM_CLOUD_003 -> "Add a ServiceUserMapper mapping and declare the service user via repoinit.";
            case AEM_CLOUD_004 -> "Replace the deprecated API with the supported AEM as a Cloud Service equivalent.";
            case AEM_CLOUD_005 -> "Move the configuration to a valid runmode folder (config, config.author, config.publish, config.dev, ...) or delete it.";
            case AEM_QUAL_006 -> "Do not store request-scoped resolvers or sessions in component fields; obtain them per request and close them.";
            case AEM_DEAD_005 -> "Reference the category from an HTL/JSP template or another clientlib, or delete the clientlib.";
            case AEM_SEC_002 -> "Validate redirects and outbound URLs against an allowlist; never pass request parameters directly.";
            case AEM_PERF_004 -> "Add a result limit (p.limit / query LIMIT) to bound the query.";
            case AEM_SEC_001 -> "Remove context='unsafe' and use a properly escaped context.";
        };
    }

    private static RuleDescriptor unknown(String id) {
        boolean sonar = id != null && id.startsWith("java:S");
        return new RuleDescriptor(
                id == null ? "UNKNOWN" : id,
                id == null ? "Unknown" : id,
                Severity.WARNING,
                sonar ? "SonarJava rule " + id : "Unknown rule " + id,
                sonar ? "Embedded SonarJava analysis finding." : "No registered metadata for this rule id.",
                sonar ? "https://rules.sonarsource.com/java/" :
                        "https://github.com/rohithv16/aem-code-analyser#readme",
                sonar ? List.of("sonar") : List.of(),
                "",
                null,
                RuleType.CODE_SMELL,
                sonar ? 10 : 5);
    }
}
