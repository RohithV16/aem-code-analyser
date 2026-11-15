package io.github.aemtools.analyser.core.api;

import io.github.aemtools.analyser.core.model.report.AnalysisReport;

/**
 * Core analysis engine contract.
 */
public interface AemCodeAnalyser {

    /**
     * Executes the full pipeline: discovery, parsing, graph construction and rule evaluation.
     *
     * @param context analysis input parameters
     * @return the aggregated analysis report
     * @throws AnalysisException when the analysis cannot be completed
     */
    AnalysisReport analyze(AnalysisContext context) throws AnalysisException;
}
