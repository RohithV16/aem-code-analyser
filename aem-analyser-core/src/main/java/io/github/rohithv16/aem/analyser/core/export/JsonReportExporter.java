package io.github.rohithv16.aem.analyser.core.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.rohithv16.aem.analyser.core.baseline.Fingerprint;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.GateVerdict;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issue #36: the neutral, provider-independent machine-readable report.
 *
 * <p>Unlike {@link SonarReportExporter} (Sonar's generic issue schema) and
 * {@link GitLabCodeQualityExporter} (Code Climate), this document is this tool's own contract: a
 * dashboard, a bespoke gate script or an MCP/LLM tool can read one documented file instead of
 * scraping ANSI console text or a multi-thousand-line SARIF run.
 *
 * <p>The document is deliberately free of timestamps and durations so two runs over an unchanged
 * tree serialise byte-identically; every object is a {@link LinkedHashMap} because {@code Map.of}
 * randomises iteration order per JVM.
 */
public final class JsonReportExporter implements ReportExporter {

    /** Version of the document contract, bumped only on a breaking change. */
    static final int SCHEMA_VERSION = 1;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public JsonReportExporter() {
        // Never close a writer the caller owns (the CLI may still print to it).
        mapper.getFactory().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
    }

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.JSON;
    }

    @Override
    public void export(AnalysisReport report, Writer writer) throws ExportException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("tool", ordered(
                "name", "aem-code-analyser",
                "version", VersionInfo.current()));
        root.put("summary", summary(report));

        List<Map<String, Object>> violations = new ArrayList<>();
        for (Violation violation : report.violations()) {
            violations.add(violation(violation));
        }
        root.put("violations", violations);

        try {
            mapper.writeValue(writer, root);
            writer.flush();
        } catch (IOException e) {
            throw new ExportException("Failed to serialize JSON report: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> summary(AnalysisReport report) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", report.violations().size());
        summary.put("error", report.countBySeverity(Severity.ERROR));
        summary.put("warning", report.countBySeverity(Severity.WARNING));
        summary.put("info", report.countBySeverity(Severity.INFO));
        GateVerdict verdict = report.gateVerdict();
        if (verdict != null) {
            summary.put("gate", ordered(
                    "status", verdict.status().name(),
                    "reason", verdict.reason()));
        }
        return summary;
    }

    /**
     * One finding. Field names are part of the contract: {@code ruleId}/{@code filePath} match the
     * model, {@code startLine} etc. are 1-based like every other exporter, and {@code fingerprint}
     * is the same stable identity baselines and SARIF {@code partialFingerprints} use.
     */
    private Map<String, Object> violation(Violation violation) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ruleId", violation.ruleId());
        entry.put("severity", violation.severity().name());
        if (violation.subtype() != null && !violation.subtype().isBlank()) {
            entry.put("subtype", violation.subtype());
        }
        entry.put("message", violation.message());
        entry.put("filePath", violation.filePath());
        entry.put("startLine", violation.region().startLine());
        entry.put("startColumn", violation.region().startColumn());
        entry.put("endLine", violation.region().endLine());
        entry.put("endColumn", violation.region().endColumn());
        violation.targetPidOpt().ifPresent(pid -> entry.put("targetPid", pid));
        entry.put("fingerprint", Fingerprint.of(violation));
        violation.remediationOpt().ifPresent(remediation -> entry.put("remediation", remediation));
        entry.put("rule", rule(violation.ruleId()));
        return entry;
    }

    /** Rule metadata for a finding; ids produced by another engine may not be in the registry. */
    private Map<String, Object> rule(String ruleId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", ruleId);
        RuleDescriptor descriptor = RuleRegistry.find(ruleId).orElse(null);
        if (descriptor == null) {
            metadata.put("name", ruleId);
            metadata.put("tags", List.of());
            return metadata;
        }
        metadata.put("name", descriptor.name());
        metadata.put("shortDescription", descriptor.shortDescription());
        metadata.put("tags", descriptor.tags());
        if (descriptor.cwe() != null) {
            metadata.put("cwe", descriptor.cwe());
        }
        metadata.put("helpUri", descriptor.helpUri());
        return metadata;
    }

    /** Insertion-ordered map so serialisation is stable across JVMs. */
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
}
