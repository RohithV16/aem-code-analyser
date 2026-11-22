package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisDiagnostic;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleCatalog;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FEAT-REP-01: canonical OASIS SARIF 2.1.0 exporter.
 * Emits {@code ruleIndex} (0-based), {@code %SRCROOT%} originalUriBaseIds and 1-based coordinates.
 */
public final class SarifReportExporter implements ReportExporter {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.SARIF;
    }

    @Override
    public void export(AnalysisReport report, Writer writer) throws ExportException {
        Map<String, Object> sarif = new LinkedHashMap<>();
        sarif.put("$schema", "https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/schemas/sarif-schema-2.1.0.json");
        sarif.put("version", "2.1.0");

        List<Map<String, Object>> ruleDefinitions = new ArrayList<>();
        Map<String, Integer> ruleIndexes = new LinkedHashMap<>();
        for (io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor rule
                : io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry.all()) {
            ruleIndexes.put(rule.id(), ruleDefinitions.size());
            ruleDefinitions.add(ruleDefinition(rule));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Violation violation : report.violations()) {
            // Accepted findings reach violations() only in the legacy --baseline-report mode, where
            // an INFO copy is kept for renderers without suppression support; SARIF emits the
            // untouched, real-level copy from acceptedViolations() instead of both.
            if (violation.isAccepted()) {
                continue;
            }
            results.add(result(violation, ruleIndexes, ruleDefinitions, false));
        }
        for (Violation violation : report.acceptedViolations()) {
            results.add(result(violation, ruleIndexes, ruleDefinitions, true));
        }

        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "aem-code-analyser");
        driver.put("version", VersionInfo.current());
        driver.put("informationUri", "https://github.com/rohithv16/aem-code-analyser");
        driver.put("rules", ruleDefinitions);

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", ordered("driver", driver));
        // Every object here is insertion-ordered: Map.of randomises its iteration order per JVM,
        // which made identical runs serialise uri/description in a different order and broke
        // caching, signing, diffing and baseline checks of the report bytes.
        run.put("originalUriBaseIds", ordered("%SRCROOT%", ordered(
                "uri", "file:///",
                "description", ordered("text", "Repository source root directory."))));
        if (!report.diagnostics().isEmpty()) {
            List<Map<String, Object>> notifications = new ArrayList<>();
            for (AnalysisDiagnostic diagnostic : report.diagnostics()) {
                Map<String, Object> notification = new LinkedHashMap<>();
                notification.put("level", switch (diagnostic.kind()) {
                    case JAVA_PARSE_ERROR, CONFIG_PARSE_ERROR -> "warning";
                    default -> "note";
                });
                String location = diagnostic.line() > 0
                        ? diagnostic.path() + ":" + diagnostic.line()
                        : diagnostic.path();
                notification.put("message", ordered("text",
                        diagnostic.kind() + " at " + location + ": " + diagnostic.reason()));
                notifications.add(notification);
            }
            run.put("invocations", List.of(ordered(
                    "executionSuccessful", true,
                    "toolExecutionNotifications", notifications)));
        }
        run.put("results", results);
        sarif.put("runs", List.of(run));

        try {
            mapper.writeValue(writer, sarif);
        } catch (IOException e) {
            throw new ExportException("Failed to serialize SARIF report: " + e.getMessage(), e);
        }
    }

    /**
     * Builds one {@code results[]} entry. An accepted finding keeps its real {@code level}, message,
     * subtype and region and additionally carries {@code baselineState} plus an accepted
     * {@code suppressions[]} entry, so a consumer shows "exists, accepted" instead of either a
     * reopened alert or no finding at all.
     */
    private Map<String, Object> result(Violation violation,
                                       Map<String, Integer> ruleIndexes,
                                       List<Map<String, Object>> ruleDefinitions,
                                       boolean accepted) {
        if (!ruleIndexes.containsKey(violation.ruleId())) {
            var descriptor = io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry
                    .get(violation.ruleId());
            ruleIndexes.put(violation.ruleId(), ruleDefinitions.size());
            ruleDefinitions.add(ruleDefinition(descriptor));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", violation.ruleId());
        result.put("ruleIndex", ruleIndexes.get(violation.ruleId()));
        result.put("level", sarifLevel(violation.severity()));
        StringBuilder messageText = new StringBuilder(violation.message());
        violation.remediationOpt().ifPresent(r -> messageText.append(" Remediation: ").append(r));
        result.put("message", ordered("text", messageText.toString()));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("subtype", violation.subtype());
        violation.targetPidOpt().ifPresent(pid -> properties.put("targetPid", pid));
        result.put("properties", properties);
        // Stable identity so GitHub code scanning tracks the same alert across unrelated edits.
        result.put("partialFingerprints", ordered("aemAnalyserFingerprint/v1",
                io.github.rohithv16.aem.analyser.core.baseline.Fingerprint.of(violation)));
        result.put("locations", List.of(location(violation.filePath(), violation.region())));
        if (!violation.relatedLocations().isEmpty()) {
            List<Map<String, Object>> related = new ArrayList<>();
            int id = 1;
            for (RelatedLocation relatedLocation : violation.relatedLocations()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id++);
                entry.put("physicalLocation", physicalLocation(relatedLocation.filePath(), relatedLocation.region()));
                entry.put("message", ordered("text",
                        relatedLocation.message()));
                related.add(entry);
            }
            result.put("relatedLocations", related);
        }
        if (accepted) {
            // "unchanged" is the SARIF baseline state for a finding the baseline already contains;
            // the suppression then carries where the waiver came from, so the audit trail lives in
            // the artefact instead of one console line.
            result.put("baselineState", "unchanged");
            result.put("suppressions", List.of(suppression(violation)));
        }
        return result;
    }

    private Map<String, Object> suppression(Violation violation) {
        if (violation.acceptance()
                == io.github.rohithv16.aem.analyser.core.model.report.AcceptanceOrigin.BASELINE) {
            return ordered(
                    "kind", "external",
                    "status", "accepted",
                    "justification", "baseline:" + io.github.rohithv16.aem.analyser.core.baseline
                            .Fingerprint.of(violation));
        }
        return ordered(
                "kind", "inSource",
                "status", "accepted",
                "justification", "aem-analyser-disable directive");
    }

    private Map<String, Object> ruleDefinition(RuleDescriptor rule) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("id", rule.id());
        // SARIF's way to say "this rule supersedes those ids". The replacement descriptor carries the
        // retired id so a consumer that still stores AEM-QUAL-006 can map its old alerts onto
        // AEM-QUAL-004 instead of leaving them orphaned.
        List<String> deprecatedIds = deprecatedIds(rule.id());
        if (!deprecatedIds.isEmpty()) {
            definition.put("deprecatedIds", deprecatedIds);
        }
        definition.put("name", rule.name());
        definition.put("shortDescription", ordered("text", rule.shortDescription()));
        definition.put("fullDescription", ordered("text", rule.fullDescription()));
        definition.put("helpUri", rule.helpUri());
        definition.put("help", ordered("text", helpText(rule), "markdown", helpMarkdown(rule)));
        Map<String, Object> properties = new LinkedHashMap<>();
        if (!rule.tags().isEmpty()) {
            properties.put("tags", rule.tags());
        }
        // Lifecycle metadata travels with the descriptor, so an API consumer can tell a retired id
        // from a live one without parsing our HTML page.
        try {
            var ruleId = io.github.rohithv16.aem.analyser.core.model.report.RuleId.fromId(rule.id());
            properties.put("status", ruleId.status().wireName());
            properties.put("since", ruleId.since());
            ruleId.replacedBy().ifPresent(replacement -> properties.put("replacedBy", replacement));
        } catch (IllegalArgumentException unknown) {
            // A descriptor for an id outside RuleId (none today) simply carries no lifecycle fields.
        }
        if (rule.cwe() != null) {
            properties.put("cwe", rule.cwe());
        }
        // GitHub code scanning ranks an alert by properties.security-severity; a security-tagged rule
        // without it is uploaded "with no security severity" and lands unranked in the alert list.
        // Non-security rules instead advertise their gate severity, so a consumer can show the same
        // Error/Warning split the CLI gates on without inventing a CVSS score for a code smell.
        if (rule.securityRanked()) {
            properties.put("security-severity", String.format(java.util.Locale.ROOT, "%.1f", rule.securitySeverity()));
        } else {
            properties.put("problem", ordered("severity", sarifLevel(rule.defaultSeverity())));
        }
        properties.put("precision", rule.precision());
        definition.put("properties", properties);
        // SARIF's own way of saying "this descriptor exists but the rule can never fire". Only
        // retired ids are disabled: an id produced by another engine (AEM-DUP-010 via PMD CPD) is
        // still active.
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("level", sarifLevel(rule.defaultSeverity()));
        if (RuleCatalog.RETIRED.contains(rule.id())) {
            configuration.put("enabled", false);
        }
        definition.put("defaultConfiguration", configuration);
        return definition;
    }

    /** Ids this rule supersedes: every retired or deprecated id whose {@code replacedBy} names it. */
    private static List<String> deprecatedIds(String ruleId) {
        List<String> ids = new ArrayList<>();
        for (io.github.rohithv16.aem.analyser.core.model.report.RuleId candidate
                : io.github.rohithv16.aem.analyser.core.model.report.RuleId.values()) {
            if (candidate.replacedBy().map(ruleId::equals).orElse(false)) {
                ids.add(candidate.id());
            }
        }
        return ids;
    }

    /** Plain-text rule help: the long description plus the remediation the CLI already prints. */
    private static String helpText(RuleDescriptor rule) {
        StringBuilder text = new StringBuilder(rule.fullDescription());
        if (rule.remediation() != null && !rule.remediation().isBlank()) {
            text.append("\n\nRemediation: ").append(rule.remediation());
        }
        return text.toString();
    }

    /**
     * Markdown rule help built from the remediation text that already exists in the registry, so the
     * guidance shown in the code-scanning UI is the same advice {@code --explain} prints instead of a
     * second, drifting copy.
     */
    private static String helpMarkdown(RuleDescriptor rule) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("### ").append(rule.id()).append(" — ").append(rule.name()).append("\n\n")
                .append(rule.fullDescription());
        if (rule.remediation() != null && !rule.remediation().isBlank()) {
            markdown.append("\n\n**Remediation:** ").append(rule.remediation());
        }
        if (rule.cwe() != null) {
            markdown.append("\n\n**Weakness:** ").append(rule.cwe());
        }
        markdown.append("\n\n[Rule documentation](").append(rule.helpUri()).append(')');
        return markdown.toString();
    }

    /** Percent-encodes a project-relative path for {@code artifactLocation.uri}, keeping '/'. */
    static String toUri(String path) {
        if (path == null) {
            return "";
        }
        StringBuilder encoded = new StringBuilder(path.length() + 16);
        String[] segments = path.replace('\\', '/').split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            encoded.append(encodeSegment(segments[i]));
        }
        return encoded.toString();
    }

    private static String encodeSegment(String segment) {
        StringBuilder encoded = new StringBuilder(segment.length() + 8);
        for (byte b : segment.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int value = b & 0xFF;
            boolean unreserved = (value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '-' || value == '.' || value == '_' || value == '~';
            if (unreserved) {
                encoded.append((char) value);
            } else {
                encoded.append('%').append(String.format("%02X", value));
            }
        }
        return encoded.toString();
    }

    private Map<String, Object> location(String filePath, TextRegion region) {
        return ordered("physicalLocation", physicalLocation(filePath, region));
    }

    /**
     * Builds a {@link LinkedHashMap} from alternating key/value arguments. Used instead of
     * {@code Map.of} so every object in the document iterates (and therefore serialises) in
     * insertion order: {@code Map.of} randomises order per JVM, which made two runs over an
     * unchanged tree produce different bytes.
     */
    private static Map<String, Object> ordered(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("ordered() needs key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>(keyValues.length);
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * Builds the SARIF {@code physicalLocation} object itself. Kept separate from {@link #location}
     * so related locations are not wrapped twice, and ordered so repeated runs serialize
     * byte-identically.
     */
    private Map<String, Object> physicalLocation(String filePath, TextRegion region) {
        Map<String, Object> artifactLocation = new LinkedHashMap<>();
        // SARIF types uri as an RFC 3986 uri-reference: spaces and non-ASCII characters are illegal
        // raw and make GitHub/Azure reject or skip the result.
        artifactLocation.put("uri", toUri(filePath));
        artifactLocation.put("uriBaseId", "%SRCROOT%");
        int startLine = region.startLine();
        int endLine = Math.max(startLine, region.endLine());
        int startColumn = region.startColumn();
        int endColumn = region.endColumn();
        if (endLine == startLine && endColumn <= startColumn) {
            endColumn = startColumn + 1;
        }
        Map<String, Object> regionMap = new LinkedHashMap<>();
        regionMap.put("startLine", startLine);
        regionMap.put("startColumn", startColumn);
        regionMap.put("endLine", endLine);
        regionMap.put("endColumn", endColumn);

        Map<String, Object> physicalLocation = new LinkedHashMap<>();
        physicalLocation.put("artifactLocation", artifactLocation);
        physicalLocation.put("region", regionMap);
        return physicalLocation;
    }

    private String sarifLevel(io.github.rohithv16.aem.analyser.core.model.report.Severity severity) {
        return switch (severity) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "note";
        };
    }
}
