package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.baseline.Fingerprint;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitLab Code Quality report (Code Climate JSON subset).
 */
public final class GitLabCodeQualityExporter implements ReportExporter {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public GitLabCodeQualityExporter() {
        mapper.getFactory().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
    }

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.GITLAB;
    }

    @Override
    public void export(AnalysisReport report, Writer writer) throws ExportException {
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Violation violation : report.violations()) {
            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("description", violation.message()
                    + violation.remediationOpt().map(r -> " " + r).orElse(""));
            issue.put("check_name", violation.ruleId());
            issue.put("fingerprint", Fingerprint.of(violation));
            issue.put("severity", switch (violation.severity()) {
                case ERROR -> "critical";
                case WARNING -> "major";
                case INFO -> "minor";
            });
            int line = violation.region() == null ? 1 : violation.region().startLine();
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("path", violation.filePath());
            location.put("lines", Map.of("begin", line));
            issue.put("location", location);
            issues.add(issue);
        }
        try {
            mapper.writeValue(writer, issues);
            writer.flush();
        } catch (IOException e) {
            throw new ExportException("Failed to write GitLab Code Quality report: " + e.getMessage(), e);
        }
    }
}
