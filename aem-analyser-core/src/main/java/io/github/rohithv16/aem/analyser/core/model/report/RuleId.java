package io.github.rohithv16.aem.analyser.core.model.report;

/**
 * Catalogue of rule identifiers with their canonical definitions.
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
            Severity.WARNING),
    AEM_QUAL_001("AEM-QUAL-001", "PathBoundServletRegistration",
            "Sling servlet registered using paths instead of resource types (CQBP-75).",
            Severity.WARNING),
    AEM_QUAL_002("AEM-QUAL-002", "ProviderTypeImplementation",
            "Custom code implements or extends @ProviderType product interface (CQBP-84).",
            Severity.ERROR),
    AEM_QUAL_003("AEM-QUAL-003", "SlingSchedulerUsage",
            "Sling whiteboard scheduler used instead of Sling Scheduled Jobs (AMSCORE-554).",
            Severity.WARNING),
    AEM_QUAL_004("AEM-QUAL-004", "UnsafeComponentState",
            "Non-thread-safe or session-bound object stored in OSGi component field (AEM-3).",
            Severity.ERROR),
    AEM_QUAL_005("AEM-QUAL-005", "AdministrativeAccessUsage",
            "Deprecated administrative login or resource resolver API used; rejected on AEM as a Cloud Service.",
            Severity.ERROR),
    AEM_DEAD_004("AEM-DEAD-004", "BrokenHtlReference",
            "HTL template references non-existent Java class or Sling Model.",
            Severity.ERROR),
    AEM_COMP_001("AEM-COMP-001", "DeprecatedClassicUiDialog",
            "Component defines deprecated Classic UI dialog.xml instead of Granite UI _cq_dialog.",
            Severity.WARNING),
    AEM_PERF_001("AEM-PERF-001", "UnclosedResourceResolver",
            "ResourceResolver obtained via factory is not closed in try-with-resources or finally.",
            Severity.ERROR),
    AEM_PERF_002("AEM-PERF-002", "UnclosedJcrSession",
            "JCR Session obtained via repository login or adaptation is not logged out or closed.",
            Severity.ERROR),
    AEM_PERF_003("AEM-PERF-003", "OakQueryTraversalRisk",
            "JCR-SQL2 or XPath query lacks path or nodetype restriction, causing Oak traversal errors.",
            Severity.WARNING),
    AEM_CLOUD_001("AEM-CLOUD-001", "HardcodedCloudSecret",
            "Plaintext secret or password found in OSGi configuration file.",
            Severity.ERROR),
    AEM_CLOUD_002("AEM-CLOUD-002", "DeprecatedJspUsage",
            "Legacy JSP file found in repository; AEMaaCS requires HTL.",
            Severity.WARNING),
    AEM_SEC_001("AEM-SEC-001", "UnsafeHtlContextBypass",
            "HTL expression uses @ context='unsafe' bypassing XSS protection.",
            Severity.ERROR),
    AEM_CLOUD_003("AEM-CLOUD-003", "UnmappedSubserviceUser",
            "Java call to getServiceResourceResolver() has no matching ServiceUserMapperImpl.amended mapping or system user definition.",
            Severity.ERROR),
    AEM_CLOUD_004("AEM-CLOUD-004", "DeprecatedAemApiUsage",
            "Java source imports or uses an API deprecated in AEM as a Cloud Service (AMSCORE-553).",
            Severity.WARNING),
    AEM_CLOUD_005("AEM-CLOUD-005", "InvalidRunmodeFolder",
            "OSGi configuration lives in a runmode folder that is not a recognised AEM runmode.",
            Severity.WARNING),
    AEM_QUAL_006("AEM-QUAL-006", "ResolverStoredInComponentField",
            "OSGi component stores a request-scoped ResourceResolver or JCR Session in an instance field.",
            Severity.WARNING),
    AEM_DEAD_005("AEM-DEAD-005", "OrphanedClientlibCategory",
            "A client library category is never referenced by an HTL/JSP template or another client library.",
            Severity.INFO),
    AEM_SEC_002("AEM-SEC-002", "OpenRedirectOrSsrf",
            "User-controlled input flows into a redirect or outbound HTTP request.",
            Severity.WARNING),
    AEM_PERF_004("AEM-PERF-004", "UnboundedQuery",
            "JCR/Oak query has no limit and may scan an unbounded result set.",
            Severity.WARNING),
    AEM_COMP_002("AEM-COMP-002", "MissingResourceTypeComponent",
            "Literal sling:resourceType or data-sly-resource type names no component under the project's /apps prefix.",
            Severity.ERROR),
    AEM_QUAL_008("AEM-QUAL-008", "UnprovidedMandatoryReference",
            "Mandatory @Reference target is declared by project sources but registered by no component.",
            Severity.ERROR);

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
