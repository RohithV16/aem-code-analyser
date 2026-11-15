package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.io.Writer;

/**
 * GitHub Actions workflow-command annotations.
 *
 * @see <a href="https://docs.github.com/actions/using-workflows/workflow-commands-for-github-actions">Workflow commands</a>
 */
public final class GitHubAnnotationExporter implements ReportExporter {

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.GITHUB;
    }

    @Override
    public void export(AnalysisReport report, Writer writer) throws ExportException {
        try {
            for (Violation violation : report.violations()) {
                String level = switch (violation.severity()) {
                    case ERROR -> "error";
                    case WARNING -> "warning";
                    case INFO -> "notice";
                };
                int line = violation.region() == null ? 1 : violation.region().startLine();
                int column = violation.region() == null ? 1 : violation.region().startColumn();
                String message = violation.message()
                        + violation.remediationOpt().map(r -> " Remediation: " + r).orElse("");
                writer.write("::" + level
                        + " file=" + escapeProperty(violation.filePath())
                        + ",line=" + line
                        + ",col=" + column
                        + ",title=" + escapeProperty(violation.ruleId())
                        + "::" + escapeData(message) + "\n");
            }
            writer.flush();
        } catch (IOException e) {
            throw new ExportException("Failed to write GitHub annotations: " + e.getMessage(), e);
        }
    }

    private String escapeData(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }

    private String escapeProperty(String value) {
        return escapeData(value)
                .replace(":", "%3A")
                .replace(",", "%2C");
    }
}
