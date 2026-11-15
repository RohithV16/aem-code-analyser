package io.github.aemtools.analyser.core.export;

import io.github.aemtools.analyser.core.model.graph.TextRegion;
import io.github.aemtools.analyser.core.model.report.AnalysisReport;
import io.github.aemtools.analyser.core.model.report.RelatedLocation;
import io.github.aemtools.analyser.core.model.report.RuleId;
import io.github.aemtools.analyser.core.model.report.Violation;
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
        RuleId[] ruleIds = RuleId.values();
        for (int i = 0; i < ruleIds.length; i++) {
            RuleId rule = ruleIds[i];
            ruleIndexes.put(rule.id(), i);
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("id", rule.id());
            definition.put("name", rule.ruleName());
            definition.put("shortDescription", Map.of("text", rule.shortDescription()));
            definition.put("defaultConfiguration",
                    Map.of("level", sarifLevel(rule.defaultLevel())));
            ruleDefinitions.add(definition);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Violation violation : report.violations()) {
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
            result.put("locations", List.of(location(violation.filePath(), violation.region())));
            if (!violation.relatedLocations().isEmpty()) {
                List<Map<String, Object>> related = new ArrayList<>();
                int id = 1;
                for (RelatedLocation relatedLocation : violation.relatedLocations()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", id++);
                    entry.put("physicalLocation", location(relatedLocation.filePath(), relatedLocation.region()));
                    entry.put("message", Map.of("text",
                            relatedLocation.message()));
                    related.add(entry);
                }
                result.put("relatedLocations", related);
            }
            results.add(result);
        }

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", Map.of(
                "name", "aem-code-analyser",
                "version", "1.0.0",
                "informationUri", "https://github.com/aem-analyser/aem-code-analyser",
                "rules", ruleDefinitions)));
        run.put("originalUriBaseIds", Map.of("%SRCROOT%", Map.of(
                "description", Map.of("text", "Repository source root directory."))));
        run.put("results", results);
        sarif.put("runs", List.of(run));

        try {
            mapper.writeValue(writer, sarif);
        } catch (IOException e) {
            throw new ExportException("Failed to serialize SARIF report: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> location(String filePath, TextRegion region) {
        Map<String, Object> artifactLocation = new LinkedHashMap<>();
        artifactLocation.put("uri", filePath);
        artifactLocation.put("uriBaseId", "%SRCROOT%");
        Map<String, Object> regionMap = new LinkedHashMap<>();
        regionMap.put("startLine", region.startLine());
        regionMap.put("startColumn", region.startColumn());
        regionMap.put("endLine", region.endLine());
        regionMap.put("endColumn", region.endColumn());
        return Map.of("physicalLocation", Map.of(
                "artifactLocation", artifactLocation,
                "region", regionMap));
    }

    private String sarifLevel(io.github.aemtools.analyser.core.model.report.Severity severity) {
        return switch (severity) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "note";
        };
    }
}
