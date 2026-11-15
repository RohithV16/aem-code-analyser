package io.github.rohithv16.aem.analyser.core.model.report;

/**
 * Summary of test code coverage discovered from target project test artifacts.
 */
public record CoverageReport(
        boolean present,
        String sourcePath,
        int coveredLines,
        int totalLines,
        double lineCoveragePercent,
        int coveredBranches,
        int totalBranches,
        double branchCoveragePercent
) {
    public static CoverageReport notFound() {
        return new CoverageReport(false, null, 0, 0, 0.0, 0, 0, 0.0);
    }
}
