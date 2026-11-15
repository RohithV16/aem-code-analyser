package io.github.rohithv16.aem.analyser.core.history;

/**
 * One row in the append-only run history ({@code runs.jsonl}).
 */
public record RunSummary(
        String timestamp,
        String toolVersion,
        int total,
        int errors,
        int warnings,
        int infos,
        double duplicationPercent,
        long durationMillis
) {
}
