package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Line-format contract: exactly the regex vim {@code errorformat}, Emacs {@code compilation-mode}
 * and VS Code's {@code $gcc} problem matcher need, one line per finding and nothing else.
 */
class TextFormatConformanceTest {

    /** The documented shape: {@code file:line:col: <severityInitial> <ruleId> <message>}. */
    private static final Pattern LINE = Pattern.compile(
            "^[^ :][^:]*:[1-9][0-9]*:[1-9][0-9]*: [EWI] AEM-[A-Z]+-[0-9]+[A-Z]? .+$");

    private static AnalysisReport portfolioShapedReport() {
        List<String> ruleIds = new ArrayList<>(RuleRegistry.all().stream()
                .map(rule -> rule.id()).distinct().toList());
        List<Violation> violations = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            Severity severity = i < 7 ? Severity.ERROR
                    : i < 35 ? Severity.WARNING : Severity.INFO;
            violations.add(new Violation(ruleIds.get(i % ruleIds.size()), severity, null,
                    "Finding " + i + " with punctuation: quotes, commas and dots.",
                    "ui.apps/components/c" + i + ".html",
                    new TextRegion(i + 1, 1 + (i % 4), i + 1, 12), null, null, List.of()));
        }
        return new AnalysisReport(Path.of("/mock"), Map.of("modules", 1), violations, 12);
    }

    private static String export(AnalysisReport report) throws Exception {
        StringWriter writer = new StringWriter();
        new TextReportExporter().export(report, writer);
        return writer.toString();
    }

    @Test
    void everyLineMatchesTheCompilerShapeAndCountsMatch() throws Exception {
        String text = export(portfolioShapedReport());

        assertEquals(36, text.lines().count(), "one physical line per finding");
        for (String line : text.lines().toList()) {
            assertTrue(LINE.matcher(line).matches(), "line does not match the contract: " + line);
        }
    }

    @Test
    void exactlyThreeSeverityInitialsAreEmitted() throws Exception {
        String text = export(portfolioShapedReport());

        assertEquals(7, text.lines().filter(l -> l.contains(": E AEM-")).count());
        assertEquals(28, text.lines().filter(l -> l.contains(": W AEM-")).count());
        assertEquals(1, text.lines().filter(l -> l.contains(": I AEM-")).count());
    }

    @Test
    void twoRunsSerialiseIdentically() throws Exception {
        AnalysisReport report = portfolioShapedReport();

        assertEquals(sha256(export(report)), sha256(export(report)));
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
