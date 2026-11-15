package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FEAT-REP-02: SonarQube Generic Issue Import JSON exporter.
 * Paths are normalized relative to the reactor root; coordinates are transformed to
 * the 0-based Sonar space with the strict {@code endColumn > startColumn} guarantee
 * when startLine == endLine.
 */
public final class SonarReportExporter implements ReportExporter {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.SONAR;
    }

    @Override
    public void export(AnalysisReport report, Writer writer) throws ExportException {
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Violation violation : report.violations()) {
            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("engineId", "aem-code-analyser");
            issue.put("ruleId", violation.ruleId());
            issue.put("severity", sonarSeverity(violation.severity()));
            issue.put("type", issueType(violation.ruleId()));
            Map<String, Object> primary = new LinkedHashMap<>();
            primary.put("message", violation.message());
            primary.put("filePath", violation.filePath());
            primary.put("textRange", textRange(violation.region()));
            issue.put("primaryLocation", primary);
            if (!violation.relatedLocations().isEmpty()) {
                List<Map<String, Object>> secondary = new ArrayList<>();
                for (RelatedLocation related : violation.relatedLocations()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("message", related.message());
                    entry.put("filePath", related.filePath());
                    entry.put("textRange", textRange(related.region()));
                    secondary.add(entry);
                }
                issue.put("secondaryLocations", secondary);
            }
            issue.put("effortMinutes", effortMinutes(violation.ruleId()));
            issues.add(issue);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("issues", issues);
        try {
            mapper.writeValue(writer, root);
        } catch (IOException e) {
            throw new ExportException("Failed to serialize Sonar report: " + e.getMessage(), e);
        }
    }

    /**
     * SonarQube coordinate transformation f_Sonar (0-based, endColumn > startColumn on same line).
     */
    static Map<String, Integer> textRange(TextRegion region) {
        int startLine = region.startLine();
        int endLine = region.endLine();
        int startColumn = Math.max(0, region.startColumn() - 1);
        int endColumn;
        if (startLine == endLine) {
            endColumn = Math.max(startColumn + 1, region.endColumn() - 1);
        } else {
            endColumn = Math.max(1, region.endColumn() - 1);
        }
        Map<String, Integer> range = new LinkedHashMap<>();
        range.put("startLine", startLine);
        range.put("startColumn", startColumn);
        range.put("endLine", endLine);
        range.put("endColumn", endColumn);
        return range;
    }

    private String sonarSeverity(Severity severity) {
        return switch (severity) {
            case ERROR -> "BLOCKER";
            case WARNING -> "MAJOR";
            case INFO -> "INFO";
        };
    }

    private String issueType(String ruleId) {
        if (RuleId.AEM_DEAD_001.id().equals(ruleId) ||
                RuleId.AEM_DEAD_004.id().equals(ruleId) ||
                RuleId.AEM_DUP_001.id().equals(ruleId) ||
                RuleId.AEM_QUAL_002.id().equals(ruleId) ||
                RuleId.AEM_QUAL_004.id().equals(ruleId) ||
                RuleId.AEM_PERF_001.id().equals(ruleId) ||
                RuleId.AEM_PERF_002.id().equals(ruleId)) {
            return "BUG";
        }
        if (RuleId.AEM_QUAL_005.id().equals(ruleId) ||
                RuleId.AEM_CLOUD_001.id().equals(ruleId) ||
                RuleId.AEM_SEC_001.id().equals(ruleId)) {
            return "VULNERABILITY";
        }
        return "CODE_SMELL";
    }

    private int effortMinutes(String ruleId) {
        return switch (RuleId.fromId(ruleId)) {
            case AEM_DEAD_001 -> 15;
            case AEM_DEAD_002A, AEM_DEAD_002B -> 10;
            case AEM_DEAD_003A -> 30;
            case AEM_DEAD_003B -> 5;
            case AEM_DEAD_003C -> 10;
            case AEM_DEAD_004 -> 15;
            case AEM_COMP_001 -> 20;
            case AEM_DUP_001 -> 20;
            case AEM_DUP_002 -> 15;
            case AEM_DUP_003 -> 15;
            case AEM_DUP_010 -> 25;
            case AEM_QUAL_001 -> 15;
            case AEM_QUAL_002 -> 30;
            case AEM_QUAL_003 -> 20;
            case AEM_QUAL_004 -> 20;
            case AEM_QUAL_005 -> 25;
            case AEM_PERF_001 -> 30;
            case AEM_PERF_002 -> 30;
            case AEM_PERF_003 -> 25;
            case AEM_CLOUD_001 -> 15;
            case AEM_CLOUD_002 -> 30;
            case AEM_SEC_001 -> 20;
            case AEM_CLOUD_003 -> 25;
        };
    }
}
