package io.github.rohithv16.aem.analyser.core.rules.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The published two-sided probe evidence, loaded from {@code /rule-evidence.json}.
 *
 * <p>The resource is generated from {@code validation/probes/evidence.json} and the
 * {@code RuleId} lifecycle metadata by {@code scripts/generate-rule-evidence.py}, so the jar, the
 * published {@code /evidence/} copy and {@code --list-rules --format json} cannot disagree. It is
 * read-only at runtime: a probe path is evidence, not a tunable.
 */
public final class RuleEvidence {

    private static final String RESOURCE = "/rule-evidence.json";
    private static final Map<String, Row> BY_ID = load();

    private RuleEvidence() {
    }

    /**
     * @param id              canonical rule id
     * @param docAnchor       anchor on the generated issue reference
     * @param status          active/deprecated/retired
     * @param since           first release that declared the id
     * @param replacedBy      replacement id, or {@code null}
     * @param positiveProbe   path of the deliberate defect, or {@code null} for a retired id
     * @param positiveLine    1-based line that must produce exactly one finding
     * @param positiveSubtype subtype that disambiguates several findings on one line
     * @param negativeProbe   clean-twin path that must produce zero findings
     */
    public record Row(String id, String docAnchor, String status, String since, String replacedBy,
                      String positiveProbe, Integer positiveLine, String positiveSubtype,
                      String negativeProbe) {
    }

    /** @return the evidence row for a known id, empty when the id is unknown or has no row */
    public static java.util.Optional<Row> find(String ruleId) {
        if (ruleId == null) {
            return java.util.Optional.empty();
        }
        Row row = BY_ID.get(ruleId);
        if (row == null) {
            row = BY_ID.get(ruleId.toUpperCase(java.util.Locale.ROOT));
        }
        return java.util.Optional.ofNullable(row);
    }

    public static List<Row> all() {
        return List.copyOf(BY_ID.values());
    }

    private static Map<String, Row> load() {
        Map<String, Row> rows = new LinkedHashMap<>();
        try (InputStream in = RuleEvidence.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return java.util.Collections.emptyMap();
            }
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode node : root.path("rules")) {
                String id = node.path("id").asText("");
                if (id.isBlank()) {
                    continue;
                }
                rows.put(id, new Row(
                        id,
                        node.path("docAnchor").asText(id.toLowerCase(java.util.Locale.ROOT)),
                        node.path("status").asText("active"),
                        node.path("since").asText(""),
                        node.path("replacedBy").isNull() ? null : node.path("replacedBy").asText(null),
                        node.path("positiveProbe").isNull() ? null : node.path("positiveProbe").asText(null),
                        node.path("positiveLine").isNull() || node.path("positiveLine").isMissingNode()
                                ? null : node.path("positiveLine").asInt(),
                        node.path("positiveSubtype").isNull() ? null : node.path("positiveSubtype").asText(null),
                        node.path("negativeProbe").isNull() ? null : node.path("negativeProbe").asText(null)));
            }
        } catch (IOException | RuntimeException unreadable) {
            // A missing or malformed generated resource must not stop a scan; the catalogue simply
            // reports no probe paths, which the probe script catches.
            return java.util.Collections.emptyMap();
        }
        return java.util.Collections.unmodifiableMap(rows);
    }
}
