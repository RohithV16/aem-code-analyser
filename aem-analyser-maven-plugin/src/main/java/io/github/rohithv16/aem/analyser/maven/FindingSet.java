package io.github.rohithv16.aem.analyser.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rohithv16.aem.analyser.core.baseline.Fingerprint;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Issue #47: reads a previous run's findings out of an artefact instead of out of repository state.
 *
 * <p>Two formats are accepted, because those are the two a CI job already has on disk:
 * <ul>
 *   <li>this tool's SARIF — the identity is {@code partialFingerprints.aemAnalyserFingerprint/v1},
 *       which the exporter emits for exactly this purpose. SARIF is parsed as JSON, never scraped:
 *       a message can contain braces, quotes and newlines, so string matching would be wrong.</li>
 *   <li>a baseline JSON ({@code {schemaVersion, entries[{fingerprint,…}]}}), which is just a set of
 *       the same fingerprints.</li>
 * </ul>
 *
 * <p>Matching never uses line or column: a finding that merely moved is the same finding, which is
 * the whole point of a delta gate on a busy branch. Fingerprints are the primary identity; a SARIF
 * produced by another tool (or with the fingerprint stripped) falls back to
 * rule + path + message, with the remediation suffix the SARIF writer appends removed so the two
 * sides stay comparable.
 */
final class FindingSet {

    private static final String FINGERPRINT_KEY = "aemAnalyserFingerprint/v1";
    private static final String REMEDIATION_SUFFIX = " Remediation: ";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One finding as it appears in a report.
     *
     * @param fingerprint stable identity, or {@code null} when the source document had none
     * @param contentKey  rule + normalised path + normalised message, used when no fingerprint exists
     */
    record Finding(String ruleId, String path, String message, Severity severity,
                   String fingerprint, String contentKey) {

        /** The identity a diff should use: the fingerprint when present, else the content key. */
        String key() {
            return fingerprint != null ? fingerprint : contentKey;
        }
    }

    private FindingSet() {
    }

    /** Findings of a live analysis: every violation already carries a fingerprint. */
    static List<Finding> fromViolations(List<Violation> violations) {
        List<Finding> findings = new ArrayList<>();
        for (Violation violation : violations == null ? List.<Violation>of() : violations) {
            findings.add(new Finding(
                    violation.ruleId(),
                    violation.filePath(),
                    violation.message(),
                    violation.severity(),
                    Fingerprint.of(violation),
                    contentKey(violation.ruleId(), violation.filePath(), violation.message())));
        }
        return findings;
    }

    /**
     * Reads a SARIF or baseline document.
     *
     * @throws IOException when the file is not valid JSON or is neither of the accepted shapes
     */
    static List<Finding> read(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
        return parse(root);
    }

    /** Package-visible so a parser test can feed a document straight from a string. */
    static List<Finding> read(String json) throws IOException {
        return parse(MAPPER.readTree(json));
    }

    private static List<Finding> parse(JsonNode root) throws IOException {
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new IOException("report is empty");
        }
        if (root.has("runs")) {
            return fromSarif(root);
        }
        if (root.has("entries")) {
            return fromBaseline(root);
        }
        throw new IOException("not a SARIF report (no 'runs') and not a baseline (no 'entries')");
    }

    private static List<Finding> fromSarif(JsonNode root) {
        List<Finding> findings = new ArrayList<>();
        for (JsonNode run : root.path("runs")) {
            for (JsonNode result : run.path("results")) {
                String ruleId = text(result, "ruleId", "unknown");
                String message = messageText(result.path("message"));
                String path = artifactUri(result);
                Severity severity = severityOf(text(result, "level", "warning"));
                String fingerprint = result.path("partialFingerprints").path(FINGERPRINT_KEY).asText(null);
                findings.add(new Finding(ruleId, path, message, severity,
                        fingerprint == null || fingerprint.isBlank() ? null : fingerprint,
                        contentKey(ruleId, path, message)));
            }
        }
        return findings;
    }

    private static List<Finding> fromBaseline(JsonNode root) {
        List<Finding> findings = new ArrayList<>();
        for (JsonNode entry : root.path("entries")) {
            String ruleId = text(entry, "ruleId", "unknown");
            String path = text(entry, "filePath", "");
            String fingerprint = text(entry, "fingerprint", "");
            // A baseline stores no message: its entries exist to be matched by fingerprint, and the
            // descriptive fields are there for a human reviewer.
            findings.add(new Finding(ruleId, path, "", severityOf(null),
                    fingerprint.isBlank() ? null : fingerprint,
                    contentKey(ruleId, path, "")));
        }
        return findings;
    }

    private static String messageText(JsonNode message) {
        if (message == null || message.isMissingNode()) {
            return "";
        }
        if (message.isTextual()) {
            return stripRemediation(message.asText());
        }
        return stripRemediation(message.path("text").asText(""));
    }

    /**
     * The SARIF writer appends " Remediation: …" to {@code message.text}; a violation's message does
     * not contain it. Removing it here keeps the content-key fallback comparable across the two
     * sides instead of reporting every finding as new.
     */
    private static String stripRemediation(String message) {
        int index = message.indexOf(REMEDIATION_SUFFIX);
        return index < 0 ? message : message.substring(0, index);
    }

    private static String artifactUri(JsonNode result) {
        JsonNode location = result.path("locations").path(0);
        JsonNode uri = location.path("physicalLocation").path("artifactLocation").path("uri");
        return decodeUri(uri.asText(""));
    }

    /** SARIF uris are percent-encoded; the analyser compares plain paths. */
    static String decodeUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        // '+' is a legal path character and must not become a space, so protect it before decoding.
        return URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static Severity severityOf(String level) {
        if (level == null) {
            return Severity.INFO;
        }
        return switch (level.toLowerCase(Locale.ROOT)) {
            case "error" -> Severity.ERROR;
            case "warning" -> Severity.WARNING;
            default -> Severity.INFO;
        };
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static String contentKey(String ruleId, String path, String message) {
        return (ruleId == null ? "" : ruleId) + "|"
                + ModuleAttributor.normalisePath(path) + "|"
                + (message == null ? "" : message.trim());
    }
}
