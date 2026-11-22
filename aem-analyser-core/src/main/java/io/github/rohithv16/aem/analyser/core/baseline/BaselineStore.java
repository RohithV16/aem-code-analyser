package io.github.rohithv16.aem.analyser.core.baseline;

import io.github.rohithv16.aem.analyser.core.export.VersionInfo;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the JSON baseline file (stable key order, LF, UTF-8).
 */
public final class BaselineStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private BaselineStore() {
    }

    public static Baseline load(Path file) throws IOException {
        Baseline baseline = MAPPER.readValue(Files.newBufferedReader(file), Baseline.class);
        if (baseline.schemaVersion() != Baseline.CURRENT_SCHEMA_VERSION) {
            String hint = baseline.schemaVersion() < Baseline.CURRENT_SCHEMA_VERSION
                    ? "That baseline predates the fingerprint fix, so its entries cannot be matched to "
                    + "current findings. Regenerate it with --update-baseline and review the diff: findings "
                    + "that were accepted may reappear, because the old scheme could hide a new finding "
                    + "behind an unrelated waiver."
                    : "That baseline was written by a newer analyser. Upgrade the analyser, or regenerate "
                    + "the baseline with --update-baseline.";
            throw new IOException("Unsupported baseline schema version " + baseline.schemaVersion()
                    + " in " + file + " (expected " + Baseline.CURRENT_SCHEMA_VERSION + "). " + hint);
        }
        return baseline;
    }

    public static void save(Path file, AnalysisReport report) throws IOException {
        List<BaselineEntry> entries = new ArrayList<>();
        for (Violation violation : report.violations()) {
            entries.add(new BaselineEntry(
                    Fingerprint.of(violation),
                    violation.ruleId(),
                    violation.filePath(),
                    violation.subtype()));
        }
        Baseline baseline = new Baseline(Baseline.CURRENT_SCHEMA_VERSION, VersionInfo.current(), entries);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        MAPPER.writeValue(file.toFile(), baseline);
    }
}
