package io.github.rohithv16.aem.analyser.core.history;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryStoreTest {

    @TempDir
    Path historyDir;

    private AnalysisReport report(int errors) {
        List<Violation> violations = java.util.stream.IntStream.range(0, errors)
                .mapToObj(i -> new Violation("AEM-DEAD-001", Severity.ERROR, "X", "m" + i,
                        "core/A.java", new TextRegion(1, 1, 1, 2), null, null, List.of()))
                .toList();
        return new AnalysisReport(Path.of("/mock"), Map.of(), violations, 12);
    }

    @Test
    void appendAndLoadRoundTrip() throws Exception {
        HistoryStore.append(historyDir, report(2));
        HistoryStore.append(historyDir, report(1));

        List<RunSummary> summaries = HistoryStore.load(historyDir, 10);

        assertEquals(2, summaries.size());
        assertEquals(2, summaries.get(0).errors());
        assertEquals(1, summaries.get(1).errors());
        assertEquals(2, Files.readAllLines(historyDir.resolve(HistoryStore.FILE_NAME)).size());
    }

    @Test
    void corruptLinesAreSkipped() throws Exception {
        Files.writeString(historyDir.resolve(HistoryStore.FILE_NAME), "{not-json}\n");
        HistoryStore.append(historyDir, report(0));

        assertEquals(1, HistoryStore.load(historyDir, 10).size());
        assertTrue(HistoryStore.load(historyDir, 10).get(0).timestamp() != null);
    }
}
