package io.github.rohithv16.aem.analyser.core.history;

import io.github.rohithv16.aem.analyser.core.export.VersionInfo;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only history of analysis runs. One JSON object per line; malformed lines are skipped.
 */
public final class HistoryStore {

    public static final String FILE_NAME = "runs.jsonl";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HistoryStore() {
    }

    public static RunSummary append(Path directory, AnalysisReport report) throws IOException {
        Files.createDirectories(directory);
        RunSummary summary = new RunSummary(
                Instant.now().toString(),
                VersionInfo.current(),
                report.violations().size(),
                (int) report.countBySeverity(Severity.ERROR),
                (int) report.countBySeverity(Severity.WARNING),
                (int) report.countBySeverity(Severity.INFO),
                report.codeMetrics() == null ? 0.0 : report.codeMetrics().duplicationPercent(),
                report.durationMillis());
        Path file = directory.resolve(FILE_NAME);
        Files.writeString(file, MAPPER.writeValueAsString(summary) + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        return summary;
    }

    public static List<RunSummary> load(Path directory, int limit) {
        Path file = directory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<RunSummary> summaries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    summaries.add(MAPPER.readValue(line, RunSummary.class));
                } catch (IOException ignored) {
                    // skip a corrupt line rather than failing the report
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        int from = Math.max(0, summaries.size() - Math.max(1, limit));
        return List.copyOf(summaries.subList(from, summaries.size()));
    }
}
