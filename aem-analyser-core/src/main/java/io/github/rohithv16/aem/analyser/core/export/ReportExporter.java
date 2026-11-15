package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;

import java.io.Writer;

/**
 * Report exporter contract (FEAT-REP-01..03).
 */
public interface ReportExporter {

    ReportFormat getFormat();

    void export(AnalysisReport report, Writer writer) throws ExportException;
}
