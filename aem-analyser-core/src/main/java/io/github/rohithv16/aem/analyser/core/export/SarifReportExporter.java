package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisDiagnostic;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
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
            result.put("message", Map.of("text", messageText.toString()));
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("subtype", violation.subtype());
            violation.targetPidOpt().ifPresent(pid -> properties.put("targetPid", pid));
            result.put("properties", properties);
            // Stable identity so GitHub code scanning tracks the same alert across unrelated edits.
            result.put("partialFingerprints", Map.of("aemAnalyserFingerprint/v1",
                    io.github.rohithv16.aem.analyser.core.baseline.Fingerprint.of(violation)));
            result.put("locations", List.of(location(violation.filePath(), violation.region())));
            if (!violation.relatedLocations().isEmpty()) {
                List<Map<String, Object>> related = new ArrayList<>();
                int id = 1;
                for (RelatedLocation relatedLocation : violation.relatedLocations()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", id++);
                    entry.put("physicalLocation", physicalLocation(relatedLocation.filePath(), relatedLocation.region()));
                    entry.put("message", Map.of("text",
                            relatedLocation.message()));
                    related.add(entry);
                }
                result.put("relatedLocations", related);
            }
            results.add(result);
        }

        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "aem-code-analyser");
        driver.put("version", VersionInfo.current());
        driver.put("informationUri", "https://github.com/rohithv16/aem-code-analyser");
        driver.put("rules", ruleDefinitions);

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", driver));
        run.put("originalUriBaseIds", Map.of("%SRCROOT%", Map.of(
                "uri", "file:///",
                "description", Map.of("text", "Repository source root directory."))));
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
                notification.put("message", Map.of("text",
                        diagnostic.kind() + " at " + location + ": " + diagnostic.reason()));
                notifications.add(notification);
            }
            run.put("invocations", List.of(Map.of(
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

    private Map<String, Object> ruleDefinition(RuleDescriptor rule) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("id", rule.id());
        definition.put("name", rule.name());
        definition.put("shortDescription", Map.of("text", rule.shortDescription()));
        definition.put("fullDescription", Map.of("text", rule.fullDescription()));
        definition.put("helpUri", rule.helpUri());
        Map<String, Object> properties = new LinkedHashMap<>();
        if (!rule.tags().isEmpty()) {
            properties.put("tags", rule.tags());
        }
        if (rule.cwe() != null) {
            properties.put("cwe", rule.cwe());
        }
        if (!properties.isEmpty()) {
            definition.put("properties", properties);
        }
        definition.put("defaultConfiguration", Map.of("level", sarifLevel(rule.defaultSeverity())));
        return definition;
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
        return Map.of("physicalLocation", physicalLocation(filePath, region));
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
