package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisDiagnostic;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleCatalog;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor;
import io.github.rohithv16.aem.analyser.core.fix.AutoFixEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FEAT-REP-01: canonical OASIS SARIF 2.1.0 exporter.
 * Emits {@code ruleIndex} (0-based), {@code %SRCROOT%} originalUriBaseIds and 1-based coordinates.
 */
public final class SarifReportExporter implements ReportExporter {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.SARIF;
    }

    @Override
    public void export(AnalysisReport report, Writer writer) throws ExportException {
        Map<String, Object> sarif = new LinkedHashMap<>();
        sarif.put("$schema", "https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/schemas/sarif-schema-2.1.0.json");
        sarif.put("version", "2.1.0");

        List<Map<String, Object>> ruleDefinitions = new ArrayList<>();
        Map<String, Integer> ruleIndexes = new LinkedHashMap<>();
        for (io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor rule
                : io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry.all()) {
            ruleIndexes.put(rule.id(), ruleDefinitions.size());
            ruleDefinitions.add(ruleDefinition(rule));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        FixPlanner fixPlanner = FixPlanner.forReport(report);
        for (Violation violation : report.violations()) {
            // Accepted findings reach violations() only in the legacy --baseline-report mode, where
            // an INFO copy is kept for renderers without suppression support; SARIF emits the
            // untouched, real-level copy from acceptedViolations() instead of both.
            if (violation.isAccepted()) {
                continue;
            }
            results.add(result(violation, ruleIndexes, ruleDefinitions, false, fixPlanner));
        }
        for (Violation violation : report.acceptedViolations()) {
            results.add(result(violation, ruleIndexes, ruleDefinitions, true, fixPlanner));
        }

        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "aem-code-analyser");
        driver.put("version", VersionInfo.current());
        driver.put("informationUri", "https://github.com/rohithv16/aem-code-analyser");
        driver.put("rules", ruleDefinitions);

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", ordered("driver", driver));
        // Every object here is insertion-ordered: Map.of randomises its iteration order per JVM,
        // which made identical runs serialise uri/description in a different order and broke
        // caching, signing, diffing and baseline checks of the report bytes.
        run.put("originalUriBaseIds", ordered("%SRCROOT%", ordered(
                "uri", "file:///",
                "description", ordered("text", "Repository source root directory."))));
        if (!report.diagnostics().isEmpty()) {
            List<Map<String, Object>> notifications = new ArrayList<>();
            for (AnalysisDiagnostic diagnostic : report.diagnostics()) {
                Map<String, Object> notification = new LinkedHashMap<>();
                notification.put("level", switch (diagnostic.kind()) {
                    case JAVA_PARSE_ERROR, CONFIG_PARSE_ERROR -> "warning";
                    default -> "note";
                });
                String location = diagnostic.line() > 0
                        ? diagnostic.path() + ":" + diagnostic.line()
                        : diagnostic.path();
                notification.put("message", ordered("text",
                        diagnostic.kind() + " at " + location + ": " + diagnostic.reason()));
                notifications.add(notification);
            }
            run.put("invocations", List.of(ordered(
                    "executionSuccessful", true,
                    "toolExecutionNotifications", notifications)));
        }
        run.put("results", results);
        sarif.put("runs", List.of(run));

        try {
            mapper.writeValue(writer, sarif);
        } catch (IOException e) {
            throw new ExportException("Failed to serialize SARIF report: " + e.getMessage(), e);
        }
    }

    /**
     * Builds one {@code results[]} entry. An accepted finding keeps its real {@code level}, message,
     * subtype and region and additionally carries {@code baselineState} plus an accepted
     * {@code suppressions[]} entry, so a consumer shows "exists, accepted" instead of either a
     * reopened alert or no finding at all.
     */
    private Map<String, Object> result(Violation violation,
                                       Map<String, Integer> ruleIndexes,
                                       List<Map<String, Object>> ruleDefinitions,
                                       boolean accepted,
                                       FixPlanner fixPlanner) {
        if (!ruleIndexes.containsKey(violation.ruleId())) {
            var descriptor = io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry
                    .get(violation.ruleId());
            ruleIndexes.put(violation.ruleId(), ruleDefinitions.size());
            ruleDefinitions.add(ruleDefinition(descriptor));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", violation.ruleId());
        result.put("ruleIndex", ruleIndexes.get(violation.ruleId()));
        result.put("level", sarifLevel(violation.severity()));
        StringBuilder messageText = new StringBuilder(violation.message());
        violation.remediationOpt().ifPresent(r -> messageText.append(" Remediation: ").append(r));
        result.put("message", ordered("text", messageText.toString()));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("subtype", violation.subtype());
        violation.targetPidOpt().ifPresent(pid -> properties.put("targetPid", pid));
        result.put("properties", properties);
        // Stable identity so GitHub code scanning tracks the same alert across unrelated edits.
        result.put("partialFingerprints", ordered("aemAnalyserFingerprint/v1",
                io.github.rohithv16.aem.analyser.core.baseline.Fingerprint.of(violation)));
        result.put("locations", List.of(location(violation.filePath(), violation.region())));
        if (!violation.relatedLocations().isEmpty()) {
            List<Map<String, Object>> related = new ArrayList<>();
            int id = 1;
            for (RelatedLocation relatedLocation : violation.relatedLocations()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id++);
                entry.put("physicalLocation", physicalLocation(relatedLocation.filePath(), relatedLocation.region()));
                entry.put("message", ordered("text",
                        relatedLocation.message()));
                related.add(entry);
            }
            result.put("relatedLocations", related);
        }
        if (accepted) {
            // "unchanged" is the SARIF baseline state for a finding the baseline already contains;
            // the suppression then carries where the waiver came from, so the audit trail lives in
            // the artefact instead of one console line.
            result.put("baselineState", "unchanged");
            result.put("suppressions", List.of(suppression(violation)));
        } else {
            // Only a finding the auto-fixer can repair carries a fix: an invented edit for the other
            // rules would offer code scanning a change nobody has verified.
            Map<String, Object> fix = fixPlanner.fixFor(violation);
            if (fix != null) {
                result.put("fixes", List.of(fix));
            }
        }
        return result;
    }

    private Map<String, Object> suppression(Violation violation) {
        if (violation.acceptance()
                == io.github.rohithv16.aem.analyser.core.model.report.AcceptanceOrigin.BASELINE) {
            return ordered(
                    "kind", "external",
                    "status", "accepted",
                    "justification", "baseline:" + io.github.rohithv16.aem.analyser.core.baseline
                            .Fingerprint.of(violation));
        }
        return ordered(
                "kind", "inSource",
                "status", "accepted",
                "justification", "aem-analyser-disable directive");
    }

    private Map<String, Object> ruleDefinition(RuleDescriptor rule) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("id", rule.id());
        definition.put("name", rule.name());
        definition.put("shortDescription", ordered("text", rule.shortDescription()));
        definition.put("fullDescription", ordered("text", rule.fullDescription()));
        definition.put("helpUri", rule.helpUri());
        definition.put("help", ordered("text", helpText(rule), "markdown", helpMarkdown(rule)));
        Map<String, Object> properties = new LinkedHashMap<>();
        if (!rule.tags().isEmpty()) {
            properties.put("tags", rule.tags());
        }
        if (rule.cwe() != null) {
            properties.put("cwe", rule.cwe());
        }
        // GitHub code scanning ranks an alert by properties.security-severity; a security-tagged rule
        // without it is uploaded "with no security severity" and lands unranked in the alert list.
        // Non-security rules instead advertise their gate severity, so a consumer can show the same
        // Error/Warning split the CLI gates on without inventing a CVSS score for a code smell.
        if (rule.securityRanked()) {
            properties.put("security-severity", String.format(java.util.Locale.ROOT, "%.1f", rule.securitySeverity()));
        } else {
            properties.put("problem", ordered("severity", sarifLevel(rule.defaultSeverity())));
        }
        properties.put("precision", rule.precision());
        definition.put("properties", properties);
        // SARIF's own way of saying "this descriptor exists but the rule can never fire". Only
        // retired ids are disabled: an id produced by another engine (AEM-DUP-010 via PMD CPD) is
        // still active.
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("level", sarifLevel(rule.defaultSeverity()));
        if (RuleCatalog.RETIRED.contains(rule.id())) {
            configuration.put("enabled", false);
        }
        definition.put("defaultConfiguration", configuration);
        return definition;
    }

    /** Plain-text rule help: the long description plus the remediation the CLI already prints. */
    private static String helpText(RuleDescriptor rule) {
        StringBuilder text = new StringBuilder(rule.fullDescription());
        if (rule.remediation() != null && !rule.remediation().isBlank()) {
            text.append("\n\nRemediation: ").append(rule.remediation());
        }
        return text.toString();
    }

    /**
     * Markdown rule help built from the remediation text that already exists in the registry, so the
     * guidance shown in the code-scanning UI is the same advice {@code --explain} prints instead of a
     * second, drifting copy.
     */
    private static String helpMarkdown(RuleDescriptor rule) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("### ").append(rule.id()).append(" — ").append(rule.name()).append("\n\n")
                .append(rule.fullDescription());
        if (rule.remediation() != null && !rule.remediation().isBlank()) {
            markdown.append("\n\n**Remediation:** ").append(rule.remediation());
        }
        if (rule.cwe() != null) {
            markdown.append("\n\n**Weakness:** ").append(rule.cwe());
        }
        markdown.append("\n\n[Rule documentation](").append(rule.helpUri()).append(')');
        return markdown.toString();
    }

    /** Percent-encodes a project-relative path for {@code artifactLocation.uri}, keeping '/'. */
    static String toUri(String path) {
        if (path == null) {
            return "";
        }
        StringBuilder encoded = new StringBuilder(path.length() + 16);
        String[] segments = path.replace('\\', '/').split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            encoded.append(encodeSegment(segments[i]));
        }
        return encoded.toString();
    }

    private static String encodeSegment(String segment) {
        StringBuilder encoded = new StringBuilder(segment.length() + 8);
        for (byte b : segment.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int value = b & 0xFF;
            boolean unreserved = (value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '-' || value == '.' || value == '_' || value == '~';
            if (unreserved) {
                encoded.append((char) value);
            } else {
                encoded.append('%').append(String.format("%02X", value));
            }
        }
        return encoded.toString();
    }

    private Map<String, Object> location(String filePath, TextRegion region) {
        return ordered("physicalLocation", physicalLocation(filePath, region));
    }

    /**
     * Builds a {@link LinkedHashMap} from alternating key/value arguments. Used instead of
     * {@code Map.of} so every object in the document iterates (and therefore serialises) in
     * insertion order: {@code Map.of} randomises order per JVM, which made two runs over an
     * unchanged tree produce different bytes.
     */
    private static Map<String, Object> ordered(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("ordered() needs key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>(keyValues.length);
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * Builds the SARIF {@code physicalLocation} object itself. Kept separate from {@link #location}
     * so related locations are not wrapped twice, and ordered so repeated runs serialize
     * byte-identically.
     */
    private Map<String, Object> physicalLocation(String filePath, TextRegion region) {
        Map<String, Object> artifactLocation = new LinkedHashMap<>();
        // SARIF types uri as an RFC 3986 uri-reference: spaces and non-ASCII characters are illegal
        // raw and make GitHub/Azure reject or skip the result.
        artifactLocation.put("uri", toUri(filePath));
        artifactLocation.put("uriBaseId", "%SRCROOT%");
        int startLine = region.startLine();
        int endLine = Math.max(startLine, region.endLine());
        int startColumn = region.startColumn();
        int endColumn = region.endColumn();
        if (endLine == startLine && endColumn <= startColumn) {
            endColumn = startColumn + 1;
        }
        Map<String, Object> regionMap = new LinkedHashMap<>();
        regionMap.put("startLine", startLine);
        regionMap.put("startColumn", startColumn);
        regionMap.put("endLine", endLine);
        regionMap.put("endColumn", endColumn);

        Map<String, Object> physicalLocation = new LinkedHashMap<>();
        physicalLocation.put("artifactLocation", artifactLocation);
        physicalLocation.put("region", regionMap);
        return physicalLocation;
    }

    private String sarifLevel(io.github.rohithv16.aem.analyser.core.model.report.Severity severity) {
        return switch (severity) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "note";
        };
    }

    /**
     * Distributes the auto-fixer's edits over the SARIF results.
     *
     * <p>The planner reads each fixable file once, asks {@link AutoFixEngine} for the same edits
     * {@code --fix} would write, and hands each edit to exactly one result. Because no edit is
     * emitted twice, applying every {@code fixes} block in the report reconstructs the file
     * {@code --fix} produces — a consumer that applies all suggestions cannot double-apply one.</p>
     *
     * <p>No file is touched and no finding is invented: a rule with no deterministic transform gets
     * no {@code fixes} entry at all.</p>
     */
    static final class FixPlanner {

        private static final FixPlanner NONE = new FixPlanner(Map.of(), Map.of());

        private final Map<String, SourceText> sources;
        private final Map<String, List<AutoFixEngine.Edit>> editsByKey;
        private final Map<String, Set<Integer>> claimed = new HashMap<>();

        private FixPlanner(Map<String, SourceText> sources,
                           Map<String, List<AutoFixEngine.Edit>> editsByKey) {
            this.sources = sources;
            this.editsByKey = editsByKey;
        }

        static FixPlanner forReport(AnalysisReport report) {
            List<Violation> all = new ArrayList<>(report.violations());
            all.addAll(report.acceptedViolations());
            boolean fixable = all.stream().anyMatch(v -> AutoFixEngine.isMachineFixable(v.ruleId()));
            if (!fixable) {
                return NONE;
            }
            List<AutoFixEngine.FilePlan> plans = new AutoFixEngine().plan(report.projectRoot(), all);
            if (plans.isEmpty()) {
                return NONE;
            }
            Map<String, SourceText> sources = new HashMap<>();
            Map<String, List<AutoFixEngine.Edit>> editsByKey = new LinkedHashMap<>();
            for (AutoFixEngine.FilePlan plan : plans) {
                sources.put(plan.filePath(), SourceText.of(plan.originalContent()));
                for (AutoFixEngine.Edit edit : plan.edits()) {
                    editsByKey.computeIfAbsent(key(edit.filePath(), edit.ruleId()),
                            k -> new ArrayList<>()).add(edit);
                }
            }
            return new FixPlanner(sources, editsByKey);
        }

        /** The {@code fixes[0]} object for one finding, or {@code null} when it has no repair. */
        Map<String, Object> fixFor(Violation violation) {
            String key = key(violation.filePath(), violation.ruleId());
            List<AutoFixEngine.Edit> candidates = editsByKey.get(key);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            Set<Integer> taken = claimed.computeIfAbsent(key, k -> new HashSet<>());
            int line = violation.region() == null ? -1 : violation.region().startLine();
            List<Integer> chosen = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                if (!taken.contains(i) && candidates.get(i).line() == line) {
                    chosen.add(i);
                }
            }
            if (chosen.isEmpty()) {
                // The finding and the edit can disagree on the line (a rule reports the enclosing
                // statement). Hand over the first unclaimed edit rather than dropping the repair.
                for (int i = 0; i < candidates.size(); i++) {
                    if (!taken.contains(i)) {
                        chosen.add(i);
                        break;
                    }
                }
            }
            if (chosen.isEmpty()) {
                return null;
            }
            // A companion edit (the StandardCharsets import) belongs to the first repair in the file:
            // it is part of that change, not a suggestion of its own.
            for (int i = 0; i < candidates.size(); i++) {
                AutoFixEngine.Edit candidate = candidates.get(i);
                if (!taken.contains(i) && isImportEdit(candidate) && !chosen.contains(i)) {
                    chosen.add(i);
                }
            }
            taken.addAll(chosen);

            List<Map<String, Object>> replacements = new ArrayList<>();
            for (int index : chosen) {
                replacements.add(replacement(candidates.get(index)));
            }
            AutoFixEngine.Edit first = candidates.get(chosen.get(0));
            String description = "Fix " + violation.ruleId() + ": " + first.description()
                    + (chosen.size() > 1 ? " (and " + (chosen.size() - 1) + " more edit(s))" : "");
            return ordered(
                    "description", ordered("text", description),
                    "artifactChanges", List.of(ordered(
                            "artifactLocation", ordered("uri", toUri(first.filePath()),
                                    "uriBaseId", "%SRCROOT%"),
                            "replacements", replacements)));
        }

        private Map<String, Object> replacement(AutoFixEngine.Edit edit) {
            Map<String, Object> replacement = new LinkedHashMap<>();
            SourceText source = sources.get(edit.filePath());
            if (source != null && !edit.insertion()) {
                int[] start = source.position(edit.startOffset());
                int[] end = source.position(edit.endOffset());
                replacement.put("deletedRegion", ordered(
                        "startLine", start[0], "startColumn", start[1],
                        "endLine", end[0], "endColumn", end[1]));
            }
            if (!edit.insertedText().isEmpty()) {
                replacement.put("insertedContent", ordered("text", edit.insertedText()));
            }
            return replacement;
        }

        private static String key(String filePath, String ruleId) {
            return (filePath == null ? "" : filePath) + '\u0000' + AutoFixEngine.canonicalRuleId(ruleId);
        }

        /** The StandardCharsets import is added by the same transform, not by a finding of its own. */
        private static boolean isImportEdit(AutoFixEngine.Edit edit) {
            return edit.insertedText().contains("import java.nio.charset.StandardCharsets;");
        }
    }

    /** A source file held once per export, with a line index for offset → SARIF coordinates. */
    private record SourceText(String content, int[] lineStarts) {

        static SourceText of(String content) {
            String[] lines = content.split("\r?\n", -1);
            int[] lineStarts = new int[lines.length];
            int offset = 0;
            for (int i = 0; i < lines.length; i++) {
                lineStarts[i] = offset;
                offset += lines[i].length();
                if (i < lines.length - 1) {
                    offset += content.startsWith("\r\n", offset) ? 2 : 1;
                }
            }
            return new SourceText(content, lineStarts);
        }

        /** 1-based {@code [line, column]} of a character offset; the end offset is exclusive. */
        int[] position(int offset) {
            int low = 0;
            int high = lineStarts.length - 1;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (lineStarts[mid] <= offset) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            return new int[]{low + 1, offset - lineStarts[low] + 1};
        }
    }
}
