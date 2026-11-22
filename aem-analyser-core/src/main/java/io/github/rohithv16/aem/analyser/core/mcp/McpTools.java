package io.github.rohithv16.aem.analyser.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rohithv16.aem.analyser.core.api.AnalysisContext;
import io.github.rohithv16.aem.analyser.core.api.AnalysisException;
import io.github.rohithv16.aem.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.baseline.Fingerprint;
import io.github.rohithv16.aem.analyser.core.config.AnalyserConfig;
import io.github.rohithv16.aem.analyser.core.config.AnalyserConfigLoader;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleCatalog;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The four MCP tools served by {@link McpServer}: {@code analyse}, {@code list_rules},
 * {@code explain_rule} and {@code explain_finding}.
 *
 * <p>Each tool answers from the same registry and the same analysis engine the CLI gate uses, so an
 * agent in the editor cannot derive a different answer from the one that fails the build. The
 * analysis payload is deliberately compact — rule id, severity, location, message, remediation and
 * fingerprint — while the rule tools carry the full registry metadata (description, tags, CWE,
 * effort, documentation link).
 */
public final class McpTools {

    public static final String ANALYSE = "analyse";
    public static final String LIST_RULES = "list_rules";
    public static final String EXPLAIN_RULE = "explain_rule";
    public static final String EXPLAIN_FINDING = "explain_finding";

    /** A tool invocation the client asked for incorrectly: unknown id, unknown tag, bad root. */
    public static final class ToolException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ToolException(String message) {
            super(message);
        }
    }

    /** One entry of the dispatch map: the advertised definition plus the code that answers it. */
    private record Tool(String name, String description, ObjectNode inputSchema,
                        Function<JsonNode, JsonNode> handler) {
    }

    /** A finding kept in the session index so {@code explain_finding} needs no second scan. */
    private record IndexedFinding(Violation violation, String fingerprint, boolean accepted) {
    }

    private final ObjectMapper mapper;
    private final Path defaultRoot;
    private final AnalyserConfig config;
    private final boolean enableSonar;
    private final boolean enableCache;
    private final Consumer<String> log;
    private final Map<String, Tool> tools;
    /** Findings from the most recent analysis run, keyed by the fingerprint the client was given. */
    private final Map<String, IndexedFinding> findingsByFingerprint = new LinkedHashMap<>();
    private boolean defaultRootAnalysed;

    public McpTools(Path defaultRoot,
                    AnalyserConfig config,
                    boolean enableSonar,
                    boolean enableCache,
                    Consumer<String> log,
                    ObjectMapper mapper) {
        this.defaultRoot = defaultRoot.toAbsolutePath().normalize();
        this.config = config == null ? AnalyserConfig.empty() : config;
        this.enableSonar = enableSonar;
        this.enableCache = enableCache;
        this.log = log == null ? line -> { } : log;
        this.mapper = mapper;
        this.tools = buildTools();
    }

    /** The tool names in advertisement order. */
    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** The {@code tools/list} payload entries, in a stable order. */
    public ArrayNode definitions() {
        ArrayNode definitions = mapper.createArrayNode();
        for (Tool tool : tools.values()) {
            ObjectNode definition = definitions.addObject();
            definition.put("name", tool.name());
            definition.put("description", tool.description());
            definition.set("inputSchema", tool.inputSchema());
        }
        return definitions;
    }

    /**
     * Answers one {@code tools/call}.
     *
     * @param name      a tool name that {@link #has(String)} reported
     * @param arguments the request {@code arguments} object (may be {@code null})
     * @return the compact result payload, also used as {@code structuredContent}
     * @throws ToolException when the request is answerable only with an error (unknown rule/tag,
     *                       missing argument, unreadable root)
     */
    public synchronized JsonNode invoke(String name, JsonNode arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new ToolException("Unknown tool '" + name + "'. Available tools: "
                    + String.join(", ", tools.keySet()) + ".");
        }
        JsonNode args = arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments;
        if (!args.isObject()) {
            throw new ToolException("Tool '" + name + "' expects an 'arguments' object.");
        }
        return tool.handler().apply(args);
    }

    private Map<String, Tool> buildTools() {
        Map<String, Tool> dispatch = new LinkedHashMap<>();
        dispatch.put(ANALYSE, new Tool(ANALYSE,
                "Analyse an AEM project root with the same engine and configuration as the gate "
                        + "and return the compact findings: ruleId, severity, file, line, message, "
                        + "remediation and fingerprint. Pass a fingerprint to explain_finding for "
                        + "the full rule context.",
                objectSchema("root", "string",
                        "Project or reactor root to analyse. Defaults to the root the server was started for."),
                this::analyse));
        dispatch.put(LIST_RULES, new Tool(LIST_RULES,
                "List the rule catalogue (id, name, severity, type, tags, CWE, effort, one-line "
                        + "description). Optionally filter by tag (osgi, htl, security, performance, "
                        + "cloud, duplication, quality, dead-code).",
                objectSchema("tag", "string",
                        "Only rules carrying this tag, case-insensitive (e.g. 'security')."),
                this::listRules));
        dispatch.put(EXPLAIN_RULE, new Tool(EXPLAIN_RULE,
                "Explain one rule by id: full description, remediation, severity, type, tags, CWE, "
                        + "effort and documentation link. Use the ids returned by list_rules.",
                objectSchema("id", "string",
                        "Canonical rule id, e.g. AEM-DEAD-004 (case-insensitive)."),
                this::explainRule));
        dispatch.put(EXPLAIN_FINDING, new Tool(EXPLAIN_FINDING,
                "Explain one finding by the fingerprint returned by analyse: its location, message, "
                        + "remediation and the full metadata of the rule that produced it. Runs an "
                        + "analysis of the server's project root once when no analyse call preceded it.",
                objectSchema("fingerprint", "string",
                        "The 16-character fingerprint returned with each finding by analyse."),
                this::explainFinding));
        // Unmodifiable but insertion-ordered: tools/list must be stable between runs.
        return java.util.Collections.unmodifiableMap(dispatch);
    }

    private ObjectNode analyse(JsonNode args) {
        Path root = resolveRoot(args);
        AnalysisReport report = runAnalysis(root);
        index(report, root);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("root", root.toString());
        ObjectNode summary = payload.putObject("summary");
        summary.put("total", report.violations().size());
        summary.put("error", report.countBySeverity(Severity.ERROR));
        summary.put("warning", report.countBySeverity(Severity.WARNING));
        summary.put("info", report.countBySeverity(Severity.INFO));
        summary.put("accepted", report.acceptedViolations().size());
        summary.put("diagnostics", report.diagnostics().size());
        ArrayNode findings = payload.putArray("findings");
        for (Violation violation : report.violations()) {
            findings.add(findingJson(violation, Fingerprint.of(violation), false));
        }
        return payload;
    }

    private ObjectNode listRules(JsonNode args) {
        String tag = optionalText(args, "tag");
        List<RuleDescriptor> rules = RuleRegistry.all();
        if (tag != null) {
            String wanted = tag.toLowerCase(Locale.ROOT);
            rules = rules.stream()
                    .filter(rule -> rule.tags().stream().anyMatch(t -> t.equalsIgnoreCase(wanted)))
                    .toList();
            if (rules.isEmpty()) {
                throw new ToolException("No rules tagged '" + tag + "'. Known tags: " + knownTags() + ".");
            }
        }
        ObjectNode payload = mapper.createObjectNode();
        if (tag == null) {
            payload.putNull("tag");
        } else {
            payload.put("tag", tag);
        }
        payload.put("count", rules.size());
        ArrayNode array = payload.putArray("rules");
        for (RuleDescriptor rule : rules) {
            array.add(ruleJson(rule, false));
        }
        return payload;
    }

    private ObjectNode explainRule(JsonNode args) {
        String id = requiredText(args, "id");
        RuleDescriptor rule = RuleRegistry.find(id).orElseGet(() -> {
            // Embedded SonarJava findings carry ids like 'java:S106' that no AEM rule defines; the
            // registry still knows how to describe them, and an agent asking about a finding it was
            // given must not be told the id is unknown.
            if (id.regionMatches(true, 0, "java:S", 0, 6)) {
                return RuleRegistry.get(id);
            }
            throw new ToolException("Unknown rule '" + id
                    + "'. Call list_rules to see the " + RuleRegistry.all().size()
                    + " registered rule ids.");
        });
        return ruleJson(rule, true);
    }

    private ObjectNode explainFinding(JsonNode args) {
        String fingerprint = requiredText(args, "fingerprint");
        IndexedFinding finding = findingsByFingerprint.get(fingerprint);
        if (finding == null && !defaultRootAnalysed) {
            AnalysisReport report = runAnalysis(defaultRoot, true);
            index(report, defaultRoot);
            finding = findingsByFingerprint.get(fingerprint);
        }
        if (finding == null) {
            throw new ToolException("No finding with fingerprint '" + fingerprint
                    + "' in this session. Call analyse first; every finding it returns carries its"
                    + " fingerprint.");
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.set("finding", findingJson(finding.violation(), finding.fingerprint(), finding.accepted()));
        payload.set("rule", ruleJson(RuleRegistry.get(finding.violation().ruleId()), true));
        return payload;
    }

    private Path resolveRoot(JsonNode args) {
        String raw = optionalText(args, "root");
        Path root = raw == null ? defaultRoot : Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new ToolException("Root '" + root + "' is not a readable directory.");
        }
        return root;
    }

    private AnalysisReport runAnalysis(Path root) {
        return runAnalysis(root, false);
    }

    private AnalysisReport runAnalysis(Path root, boolean countAsDefaultRoot) {
        try {
            AnalyserConfig effective = root.equals(defaultRoot) ? config : AnalyserConfigLoader.loadIfPresent(root);
            AnalysisContext context = AnalysisContext.builder(root)
                    .enableSonar(enableSonar)
                    .enableCache(enableCache)
                    .build();
            long started = System.nanoTime();
            AnalysisReport report = effective.apply(new DefaultAemCodeAnalyser().analyze(context));
            if (countAsDefaultRoot) {
                defaultRootAnalysed = true;
            }
            log.accept("[INFO] mcp: analysed " + root + " — " + report.violations().size()
                    + " finding(s) in " + ((System.nanoTime() - started) / 1_000_000) + " ms");
            return report;
        } catch (AnalysisException e) {
            throw new ToolException("Analysis of '" + root + "' failed: " + oneLine(e.getMessage()));
        } catch (IOException e) {
            throw new ToolException("Could not read the analyser configuration for '" + root + "': "
                    + oneLine(e.getMessage()));
        }
    }

    private void index(AnalysisReport report, Path root) {
        findingsByFingerprint.clear();
        for (Violation violation : report.violations()) {
            String fingerprint = Fingerprint.of(violation);
            findingsByFingerprint.put(fingerprint, new IndexedFinding(violation, fingerprint, false));
        }
        for (Violation violation : report.acceptedViolations()) {
            String fingerprint = Fingerprint.of(violation);
            findingsByFingerprint.putIfAbsent(fingerprint, new IndexedFinding(violation, fingerprint, true));
        }
        if (root.equals(defaultRoot)) {
            defaultRootAnalysed = true;
        }
    }

    private ObjectNode findingJson(Violation violation, String fingerprint, boolean accepted) {
        ObjectNode finding = mapper.createObjectNode();
        finding.put("fingerprint", fingerprint);
        finding.put("ruleId", violation.ruleId());
        finding.put("severity", violation.severity() == null ? Severity.WARNING.name() : violation.severity().name());
        if (violation.subtype() != null && !violation.subtype().isBlank()) {
            finding.put("subtype", violation.subtype());
        }
        finding.put("file", violation.filePath());
        finding.put("line", violation.region().startLine());
        finding.put("column", violation.region().startColumn());
        finding.put("message", violation.message());
        if (violation.remediation() != null && !violation.remediation().isBlank()) {
            finding.put("remediation", violation.remediation());
        }
        if (violation.targetPid() != null && !violation.targetPid().isBlank()) {
            finding.put("targetPid", violation.targetPid());
        }
        if (accepted) {
            finding.put("accepted", true);
        }
        return finding;
    }

    private ObjectNode ruleJson(RuleDescriptor rule, boolean full) {
        ObjectNode json = mapper.createObjectNode();
        json.put("id", rule.id());
        json.put("name", rule.name());
        json.put("severity", rule.defaultSeverity().name());
        json.put("type", rule.type().name());
        json.put("shortDescription", rule.shortDescription());
        json.put("effortMinutes", rule.effortMinutes());
        ArrayNode tags = json.putArray("tags");
        rule.tags().forEach(tags::add);
        if (rule.cwe() != null && !rule.cwe().isBlank()) {
            json.put("cwe", rule.cwe());
        }
        json.put("helpUri", rule.helpUri());
        boolean retired = RuleCatalog.isRetired(rule.id());
        json.put("retired", retired);
        json.put("registered", RuleRegistry.find(rule.id()).isPresent());
        if (retired) {
            json.put("retirementNote", RuleCatalog.retirementNote(rule.id()));
        }
        if (full) {
            json.put("fullDescription", rule.fullDescription());
            json.put("remediation", rule.remediation());
        }
        return json;
    }

    private ObjectNode objectSchema(String property, String type, String description) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject(property).put("type", type).put("description", description);
        return schema;
    }

    private static String requiredText(JsonNode args, String field) {
        String value = optionalText(args, field);
        if (value == null) {
            throw new ToolException("Missing required argument '" + field + "'.");
        }
        return value;
    }

    private static String optionalText(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new ToolException("Argument '" + field + "' must be a string.");
        }
        String value = node.asText().strip();
        return value.isEmpty() ? null : value;
    }

    private static String knownTags() {
        Set<String> tags = new TreeSet<>();
        for (RuleDescriptor rule : RuleRegistry.all()) {
            tags.addAll(rule.tags());
        }
        return String.join(", ", tags);
    }

    /** One line, no stack trace, no embedded newline: this text ends up inside a JSON-RPC error. */
    static String oneLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        String flat = message.replaceAll("\\s+", " ").strip();
        return flat.isEmpty() ? "unknown error" : flat;
    }
}
