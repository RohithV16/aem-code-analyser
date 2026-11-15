package io.github.rohithv16.aem.analyser.core.model.report;

/**
 * Catalog of Phase 1 rule identifiers with their canonical definitions.
 */
public enum RuleId {
    AEM_DEAD_001("AEM-DEAD-001", "OrphanedOsgiConfiguration",
            "Orphaned OSGi Configuration without matching component or platform PID.",
            Severity.ERROR),
    AEM_DEAD_002A("AEM-DEAD-002A", "GhostConfigurationProperty",
            "Configuration property does not exist in target @ObjectClassDefinition.",
            Severity.WARNING),
    AEM_DEAD_002B("AEM-DEAD-002B", "UnconsumedOcdProperty",
            "OCD Attribute method is never consumed by designating Java component.",
            Severity.WARNING),
    AEM_DEAD_003A("AEM-DEAD-003A", "UnusedOsgiService",
            "OSGi Service has zero injections across bundles and is not a system entry point.",
            Severity.WARNING),
    AEM_DEAD_003B("AEM-DEAD-003B", "UnusedSlingModel",
            "Sling Model has zero Java adaptations and no resourceType binding.",
            Severity.INFO),
    AEM_DEAD_003C("AEM-DEAD-003C", "InactiveDelayedComponent",
            "Component provides no service interfaces and immediate = false.",
            Severity.WARNING),
    AEM_DUP_001("AEM-DUP-001", "DuplicateServletEndpoint",
            "Multiple OSGi servlets claim identical paths or resourceTypes/selectors/methods without ranking distinction.",
            Severity.ERROR),
    AEM_DUP_002("AEM-DUP-002", "DuplicateOsgiConfiguration",
            "Conflicting or duplicate OSGi configuration targeting the same PID in the same runmode context.",
            Severity.WARNING),
    AEM_DUP_003("AEM-DUP-003", "DuplicateSlingModel",
            "Multiple Sling Models bind to the exact same resourceType and adapter interface without condition.",
            Severity.WARNING),
    AEM_DUP_010("AEM-DUP-010", "DuplicateCodeBlock",
            "Duplicate Java code block or method clone detected across repository classes.",
            Severity.WARNING);

    private final String id;
    private final String name;
    private final String shortDescription;
    private final Severity defaultLevel;

    RuleId(String id, String name, String shortDescription, Severity defaultLevel) {
        this.id = id;
        this.name = name;
        this.shortDescription = shortDescription;
        this.defaultLevel = defaultLevel;
    }

    public String id() {
        return id;
    }

    public String ruleName() {
        return name;
    }

    public String shortDescription() {
        return shortDescription;
    }

    public Severity defaultLevel() {
        return defaultLevel;
    }

    public static RuleId fromId(String id) {
        for (RuleId rule : values()) {
            if (rule.id.equals(id)) {
                return rule;
            }
        }
        throw new IllegalArgumentException("Unknown rule id: " + id);
    }
}
