package io.github.rohithv16.aem.analyser.core.model.report;

/**
 * Summary of code size and duplication across analyzed sources.
 */
public record CodeMetrics(
        int totalJavaFiles,
        int totalJavaLines,
        int totalMethodBlocks,
        int duplicatedLines,
        int duplicateBlocks,
        double duplicationPercent
) {
}
