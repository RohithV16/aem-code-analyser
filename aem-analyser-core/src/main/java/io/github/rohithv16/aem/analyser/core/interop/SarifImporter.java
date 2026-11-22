package io.github.rohithv16.aem.analyser.core.interop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads another analyser's SARIF 2.1.0 document into {@link ForeignFinding}s.
 *
 * <p>Only the fields needed to match a location are read: the tool name, result rule id, the first
 * physical location's URI and its region. A result without a usable location is skipped rather than
 * guessed at, because a suppression is only safe when the two tools point at the same line.
 *
 * <p>This is deliberately a reader, not a merger: nothing is imported into the report until an
 * explicit id map says which of our rules a foreign rule corresponds to
 * (see {@link ExternalSuppressions}).
 */
public final class SarifImporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SarifImporter() {
    }

    public static List<ForeignFinding> read(Path sarifFile, Path projectRoot) throws IOException {
        JsonNode document;
        try {
            document = MAPPER.readTree(Files.newBufferedReader(sarifFile, StandardCharsets.UTF_8));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("Imported report is not valid JSON: " + sarifFile + " (" + e.getOriginalMessage() + ")", e);
        }
        JsonNode runs = document.path("runs");
        if (!runs.isArray()) {
            throw new IOException("Imported report has no SARIF 'runs' array: " + sarifFile);
        }
        List<ForeignFinding> findings = new ArrayList<>();
        for (JsonNode run : runs) {
            String tool = run.path("tool").path("driver").path("name").asText("external");
            for (JsonNode result : run.path("results")) {
                String ruleId = result.path("ruleId").asText("");
                if (ruleId.isBlank()) {
                    ruleId = result.path("rule").path("id").asText("");
                }
                if (ruleId.isBlank()) {
                    continue;
                }
                JsonNode location = result.path("locations").path(0);
                JsonNode physical = location.path("physicalLocation");
                String uri = physical.path("artifactLocation").path("uri").asText("");
                JsonNode region = physical.path("region");
                int startLine = region.path("startLine").asInt(0);
                int endLine = region.path("endLine").asInt(startLine);
                String normalized = normalizeUri(uri, projectRoot);
                if (normalized.isBlank() || startLine <= 0) {
                    continue;
                }
                findings.add(new ForeignFinding(tool, ruleId, normalized, startLine, endLine));
            }
        }
        return List.copyOf(findings);
    }

    /**
     * Normalises a SARIF URI into the reactor-relative, '/'-separated form our violations use.
     * SARIF permits percent-encoded URIs and absolute {@code file://} URIs; both must compare equal
     * to {@code ui.apps/src/...} or the match silently never happens.
     */
    public static String normalizeUri(String uri, Path projectRoot) {
        if (uri == null || uri.isBlank()) {
            return "";
        }
        String value = uri.strip();
        if (value.startsWith("file://")) {
            value = value.substring("file://".length());
        } else if (value.startsWith("file:")) {
            value = value.substring("file:".length());
        }
        // URLDecoder turns '+' into a space, which is wrong for a path; protect it first.
        value = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        value = value.replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.startsWith("/") && projectRoot != null) {
            try {
                Path absolute = Path.of(value).normalize();
                Path root = projectRoot.toAbsolutePath().normalize();
                if (absolute.startsWith(root)) {
                    value = root.relativize(absolute).toString().replace('\\', '/');
                }
            } catch (RuntimeException invalidPath) {
                // A URI that is not a filesystem path stays as written and simply will not match.
            }
        }
        return value;
    }
}
