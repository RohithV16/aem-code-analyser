package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.io.Writer;

/**
 * Azure Pipelines logging commands ({@code ##vso[task.logissue]}) for inline PR annotations.
 */
public final class AzurePipelinesExporter implements ReportExporter {

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.AZURE;
    }

    @Override
    public void export(AnalysisReport report, Writer writer) throws ExportException {
        try {
            for (Violation violation : report.violations()) {
                String type = switch (violation.severity()) {
                    case ERROR -> "error";
                    case WARNING -> "warning";
                    case INFO -> "info";
                };
                int line = violation.region() == null ? 1 : violation.region().startLine();
                int column = violation.region() == null ? 1 : violation.region().startColumn();
                String message = violation.message().replace("\r", " ").replace("\n", " ");
                writer.write("##vso[task.logissue type=" + type
                        + ";sourcepath=" + escape(violation.filePath())
                        + ";linenumber=" + line
                        + ";columnnumber=" + column
                        + ";code=" + escape(violation.ruleId())
                        + "]" + message + "\n");
            }
            writer.flush();
        } catch (IOException e) {
            throw new ExportException("Failed to write Azure Pipelines annotations: " + e.getMessage(), e);
        }
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("%", "%25")
                .replace(";", "%3B")
                .replace("]", "%5D")
                .replace("\r", "%0D")
                .replace("\n", "%0A");
    }
}
