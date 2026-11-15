package io.github.rohithv16.aem.analyser.core.model.report;

import java.util.List;

/**
 * Summary of code size and duplication across analyzed sources.
 */
public record CodeMetrics(
        int totalJavaFiles,
        int totalJavaLines,
        int totalMethodBlocks,
        int duplicatedLines,
        int duplicateBlocks,
        double duplicationPercent,
        List<ClassDuplication> duplicateClasses
) {
    public CodeMetrics(int totalJavaFiles, int totalJavaLines, int totalMethodBlocks,
                       int duplicatedLines, int duplicateBlocks, double duplicationPercent) {
        this(totalJavaFiles, totalJavaLines, totalMethodBlocks, duplicatedLines, duplicateBlocks, duplicationPercent, List.of());
    }

    public CodeMetrics {
        duplicateClasses = List.copyOf(duplicateClasses == null ? List.of() : duplicateClasses);
    }

    public record ClassDuplication(
            String className,
            String filePath,
            int duplicatedLines,
            int totalLines,
            double duplicationPercent
    ) {
    }
}
