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
        List<ClassDuplication> duplicateClasses,
        List<DuplicateBlock> duplicateBlockDetails
) {
    public CodeMetrics(int totalJavaFiles, int totalJavaLines, int totalMethodBlocks,
                       int duplicatedLines, int duplicateBlocks, double duplicationPercent) {
        this(totalJavaFiles, totalJavaLines, totalMethodBlocks, duplicatedLines, duplicateBlocks,
                duplicationPercent, List.of(), List.of());
    }

    public CodeMetrics(int totalJavaFiles, int totalJavaLines, int totalMethodBlocks,
                       int duplicatedLines, int duplicateBlocks, double duplicationPercent,
                       List<ClassDuplication> duplicateClasses) {
        this(totalJavaFiles, totalJavaLines, totalMethodBlocks, duplicatedLines, duplicateBlocks,
                duplicationPercent, duplicateClasses, List.of());
    }

    public CodeMetrics {
        duplicateClasses = List.copyOf(duplicateClasses == null ? List.of() : duplicateClasses);
        duplicateBlockDetails = List.copyOf(duplicateBlockDetails == null ? List.of() : duplicateBlockDetails);
    }

    public record ClassDuplication(
            String className,
            String filePath,
            int duplicatedLines,
            int totalLines,
            double duplicationPercent
    ) {
    }

    /**
     * One duplicated block pair (duplicate side plus the original it matches).
     *
     * @param duplicateFile      reactor-relative file containing the duplicate
     * @param duplicateStartLine first duplicated line (1-based)
     * @param duplicateEndLine   last duplicated line (1-based)
     * @param originalFile       reactor-relative file containing the original block
     * @param originalStartLine  first original line (1-based)
     * @param originalEndLine    last original line (1-based)
     * @param duplicatedLines    number of duplicated lines
     * @param tokens             CPD token count of the block
     */
    public record DuplicateBlock(
            String duplicateFile,
            int duplicateStartLine,
            int duplicateEndLine,
            String originalFile,
            int originalStartLine,
            int originalEndLine,
            int duplicatedLines,
            int codeLines,
            int tokens,
            String fingerprint
    ) {
        public DuplicateBlock(String duplicateFile, int duplicateStartLine, int duplicateEndLine,
                              String originalFile, int originalStartLine, int originalEndLine,
                              int duplicatedLines, int tokens) {
            this(duplicateFile, duplicateStartLine, duplicateEndLine, originalFile, originalStartLine,
                    originalEndLine, duplicatedLines, duplicatedLines, tokens, "");
        }
    }
}
