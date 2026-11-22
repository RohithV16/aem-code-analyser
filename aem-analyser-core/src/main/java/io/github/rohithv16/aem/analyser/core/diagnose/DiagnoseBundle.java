package io.github.rohithv16.aem.analyser.core.diagnose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.rohithv16.aem.analyser.core.config.AnalyserConfig;
import io.github.rohithv16.aem.analyser.core.discovery.ProjectDiscoveryEngine;
import io.github.rohithv16.aem.analyser.core.export.VersionInfo;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisDiagnostic;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleDescriptor;
import io.github.rohithv16.aem.analyser.core.util.DiagnosticSink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the redacted, offline repro bundle written by {@code --diagnose}.
 *
 * <p>The bundle is four files — the prefilled issue body, the finding as JSON, a ±5-line excerpt and
 * the environment — and nothing is transmitted. The project's own group id and root package are
 * replaced with {@code <group>}/{@code <package>}, allowlist values are replaced with counts, and
 * absolute paths never enter the archive, so a bundle can be pasted into a public issue.
 *
 * <p>The ZIP timestamps are fixed and entries are inserted in a stable order, so running the same
 * command twice over an unchanged tree produces byte-identical archives. That makes "it is offline"
 * checkable: the same command under a dead proxy has the same SHA-256.
 */
public final class DiagnoseBundle {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final long FIXED_ENTRY_TIME = LocalDate.of(1980, 1, 1)
            .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    private static final List<String> ENTRY_NAMES =
            List.of("issue.md", "finding.json", "excerpt.txt", "environment.json");
    private static final int CONTEXT_LINES = 5;

    private DiagnoseBundle() {
    }

    /**
     * @param projectRoot reactor root (used only to relativise paths and discover prefixes)
     * @param finding     the selected violation
     * @param rule        registry descriptor for the finding's rule
     * @param config      effective configuration (allowlist values are redacted)
     * @param diagnostics the run's parser diagnostics (only those for the finding's file are kept)
     * @param target      the zip file to write
     * @return the written archive
     */
    public static Path write(Path projectRoot, Violation finding, RuleDescriptor rule,
                             AnalyserConfig config, List<AnalysisDiagnostic> diagnostics,
                             Path target) throws IOException {
        Redactor redactor = Redactor.forProject(projectRoot);
        String relativePath = redactor.normalizePath(finding.filePath());
        List<String> excerpt = excerpt(projectRoot.resolve(relativePath), finding, redactor);
        List<AnalysisDiagnostic> parserDiagnostics = diagnosticsFor(diagnostics, relativePath);
        Map<String, String> configView = configView(config);
        Map<String, Object> environment = environment();

        Map<String, Object> findingJson = new LinkedHashMap<>();
        findingJson.put("rule", ruleView(rule));
        findingJson.put("finding", violationView(finding, redactor));
        findingJson.put("config", configView);
        findingJson.put("parserDiagnostics", parserDiagnostics.stream()
                .map(diagnostic -> diagnostic.kind() + " at " + redactor.redact(diagnostic.path())
                        + (diagnostic.line() > 0 ? ":" + diagnostic.line() : "") + ": "
                        + redactor.redact(diagnostic.reason()))
                .toList());
        findingJson.put("environment", environment);
        Map<String, Object> redaction = new LinkedHashMap<>();
        redaction.put("groupIds", "<redacted: " + redactor.redactedGroupIds().size() + " prefixes>");
        redaction.put("packagePrefix", redactor.redactedPackagePrefix().isBlank()
                ? "<none>" : "<redacted>");
        findingJson.put("redaction", redaction);

        String issue = issueBody(rule, finding, excerpt, configView, parserDiagnostics, environment, redactor);

        Files.createDirectories(target.toAbsolutePath().getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            put(zip, "issue.md", issue);
            put(zip, "finding.json", JSON.writeValueAsString(findingJson));
            put(zip, "excerpt.txt", String.join("\n", excerpt) + "\n");
            put(zip, "environment.json", JSON.writeValueAsString(environment));
        }
        return target;
    }

    /** The archive name, one per rule so repeated diagnoses do not overwrite each other silently. */
    public static String fileName(String ruleId) {
        return "aem-analyser-diagnose-" + ruleId.replaceAll("[^A-Za-z0-9._-]", "_") + ".zip";
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static Map<String, Object> ruleView(RuleDescriptor rule) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", rule.id());
        view.put("name", rule.name());
        view.put("severity", rule.defaultSeverity().name());
        view.put("shortDescription", rule.shortDescription());
        view.put("fullDescription", rule.fullDescription());
        view.put("remediation", rule.remediation());
        view.put("helpUri", rule.helpUri());
        view.put("tags", rule.tags());
        return view;
    }

    private static Map<String, Object> violationView(Violation finding, Redactor redactor) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ruleId", finding.ruleId());
        view.put("severity", finding.severity().name());
        view.put("subtype", finding.subtype());
        view.put("message", redactor.redact(finding.message()));
        view.put("filePath", redactor.redact(finding.filePath()));
        view.put("startLine", finding.region() == null ? 0 : finding.region().startLine());
        view.put("endLine", finding.region() == null ? 0 : finding.region().endLine());
        view.put("startColumn", finding.region() == null ? 0 : finding.region().startColumn());
        view.put("endColumn", finding.region() == null ? 0 : finding.region().endColumn());
        view.put("acceptance", finding.acceptance().name());
        return view;
    }

    private static Map<String, String> configView(AnalyserConfig config) {
        Map<String, String> view = new LinkedHashMap<>();
        view.put("schemaVersion", String.valueOf(config.schemaVersion()));
        config.rules().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> view.put("rules." + entry.getKey(),
                        "enabled=" + !entry.getValue().disabled()
                                + (entry.getValue().severity() == null ? "" : ",severity=" + entry.getValue().severity())));
        // Allowlist values are often internal service names or paths; keep the count, drop the names.
        view.put("allowlists.services", "<redacted: " + config.allowlists().services().size() + " entries>");
        view.put("allowlists.configPaths", "<redacted: " + config.allowlists().configPaths().size() + " entries>");
        return view;
    }

    private static Map<String, Object> environment() {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("tool", "aem-code-analyser");
        environment.put("toolVersion", VersionInfo.current());
        environment.put("javaVersion", System.getProperty("java.version", "unknown"));
        environment.put("javaVendor", System.getProperty("java.vendor", "unknown"));
        environment.put("os", System.getProperty("os.name", "unknown") + " "
                + System.getProperty("os.version", ""));
        return environment;
    }

    private static List<String> excerpt(Path file, Violation finding, Redactor redactor) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of("<file no longer exists: " + redactor.redact(finding.filePath()) + ">");
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int line = finding.region() == null ? 1 : Math.max(1, finding.region().startLine());
        int from = Math.max(1, line - CONTEXT_LINES);
        int to = Math.min(lines.size(), line + CONTEXT_LINES);
        List<String> excerpt = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            excerpt.add(String.format("%4d | %s", i, redactor.redact(lines.get(i - 1))));
        }
        return excerpt;
    }

    private static List<AnalysisDiagnostic> diagnosticsFor(List<AnalysisDiagnostic> diagnostics,
                                                            String relativePath) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return List.of();
        }
        String normalized = relativePath.replace('\\', '/');
        return diagnostics.stream()
                .filter(diagnostic -> {
                    String path = diagnostic.path().replace('\\', '/');
                    return path.equals(normalized) || path.endsWith("/" + normalized);
                })
                .toList();
    }

    private static String issueBody(RuleDescriptor rule, Violation finding, List<String> excerpt,
                                    Map<String, String> config, List<AnalysisDiagnostic> diagnostics,
                                    Map<String, Object> environment, Redactor redactor) {
        StringBuilder body = new StringBuilder();
        body.append("## ").append(rule.id()).append(" reports a finding I believe is wrong\n\n");
        body.append("**Rule:** `").append(rule.id()).append("` — ").append(rule.name()).append("\n\n");
        body.append("**Finding:** `").append(finding.severity()).append("` at `")
                .append(redactor.redact(finding.filePath())).append(':')
                .append(finding.region() == null ? 0 : finding.region().startLine()).append("`\n\n");
        body.append("**Message:** ").append(redactor.redact(finding.message())).append("\n\n");
        body.append("### Excerpt (redacted)\n\n```\n").append(String.join("\n", excerpt))
                .append("\n```\n\n");
        body.append("### Effective configuration\n\n```\n");
        config.forEach((key, value) -> body.append(key).append(": ").append(value).append('\n'));
        body.append("```\n\n");
        body.append("### Parser diagnostics for this file\n\n");
        if (diagnostics.isEmpty()) {
            body.append("_None._\n\n");
        } else {
            for (AnalysisDiagnostic diagnostic : diagnostics) {
                body.append("- ").append(diagnostic.kind()).append(" at ")
                        .append(redactor.redact(diagnostic.path()))
                        .append(diagnostic.line() > 0 ? ":" + diagnostic.line() : "")
                        .append(": ").append(redactor.redact(diagnostic.reason())).append('\n');
            }
            body.append('\n');
        }
        body.append("### Environment\n\n```json\n");
        try {
            body.append(JSON.writeValueAsString(environment));
        } catch (IOException e) {
            body.append("{\"toolVersion\":\"").append(environment.get("toolVersion")).append("\"}");
        }
        body.append("\n```\n\n");
        body.append("### Why this is a false positive\n\n");
        body.append("_Describe what the code actually does and why the rule should not fire._\n\n");
        body.append("---\nGenerated locally by `aem-analyser --diagnose`; nothing was transmitted.\n");
        return body.toString();
    }

    /**
     * Removes the project's own group id and package prefix from every string in the bundle.
     *
     * <p>Prefixes are discovered from the reactor's POMs and Java sources rather than guessed from
     * the rule id, and the longest prefix is replaced first so {@code com.acme.foo} becomes
     * {@code <package>.foo} instead of {@code <package>.acme.foo}.
     */
    public static final class Redactor {

        private final List<String> groupIds;
        private final String packagePrefix;

        private Redactor(List<String> groupIds, String packagePrefix) {
            this.groupIds = List.copyOf(groupIds);
            this.packagePrefix = packagePrefix == null ? "" : packagePrefix;
        }

        public static Redactor forProject(Path projectRoot) {
            Set<String> groupIds = new TreeSet<>();
            try (ProjectDiscoveryEngine discovery =
                         new ProjectDiscoveryEngine(new DiagnosticSink())) {
                for (ModuleDescriptor module : discovery.discover(projectRoot, List.of()).modules()) {
                    if (module.groupId() != null && !module.groupId().isBlank()) {
                        groupIds.add(module.groupId());
                    }
                }
            } catch (RuntimeException noReactor) {
                // A non-Maven fixture still gets package-prefix redaction below.
                groupIds.addAll(groupIdsFromPoms(projectRoot));
            }
            String packagePrefix = commonPackage(projectRoot);
            if (packagePrefix.isBlank() && !groupIds.isEmpty()) {
                packagePrefix = groupIds.iterator().next();
            }
            return new Redactor(new ArrayList<>(groupIds), packagePrefix);
        }

        private static Set<String> groupIdsFromPoms(Path projectRoot) {
            Set<String> ids = new TreeSet<>();
            try (var paths = Files.walk(projectRoot, 3)) {
                paths.filter(path -> path.getFileName() != null && path.getFileName().toString().equals("pom.xml"))
                        .limit(200)
                        .forEach(pom -> {
                            try {
                                var root = new org.apache.maven.model.io.DefaultModelReader()
                                        .read(pom.toFile(), Map.of());
                                String id = root.getGroupId();
                                if (id == null && root.getParent() != null) {
                                    id = root.getParent().getGroupId();
                                }
                                if (id != null && !id.isBlank()) {
                                    ids.add(id);
                                }
                            } catch (Exception ignored) {
                                // An unreadable POM contributes no prefix.
                            }
                        });
            } catch (IOException ignored) {
                // No walk, no prefixes.
            }
            return ids;
        }

        /** Longest common package prefix of the project's Java sources, or empty. */
        private static String commonPackage(Path projectRoot) {
            List<String> packages = new ArrayList<>();
            try (var paths = Files.walk(projectRoot)) {
                paths.filter(path -> path.toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .limit(2000)
                        .forEach(path -> {
                            try {
                                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                                    String trimmed = line.strip();
                                    if (trimmed.startsWith("package ")) {
                                        String value = trimmed.substring("package ".length()).strip();
                                        int semicolon = value.indexOf(';');
                                        if (semicolon > 0) {
                                            packages.add(value.substring(0, semicolon).strip());
                                        }
                                        break;
                                    }
                                }
                            } catch (IOException ignored) {
                                // An unreadable source contributes no package.
                            }
                        });
            } catch (IOException ignored) {
                return "";
            }
            if (packages.isEmpty()) {
                return "";
            }
            String common = packages.get(0);
            for (String candidate : packages) {
                while (!candidate.equals(common) && !candidate.startsWith(common + ".")) {
                    int dot = common.lastIndexOf('.');
                    if (dot < 0) {
                        return "";
                    }
                    common = common.substring(0, dot);
                }
            }
            return common;
        }

        /** Redacts group ids and the root package; idempotent and safe on null. */
        public String redact(String value) {
            if (value == null || value.isEmpty()) {
                return value == null ? "" : value;
            }
            String redacted = value;
            if (!packagePrefix.isBlank()) {
                redacted = redacted.replace(packagePrefix, "<package>");
                // A path spells the package with '/', so redact that form too; otherwise the file
                // path still names the internal namespace even though class names are masked.
                redacted = redacted.replace(packagePrefix.replace('.', '/'), "<package>");
            }
            for (String groupId : groupIds) {
                redacted = redacted.replace(groupId, "<group>");
                redacted = redacted.replace(groupId.replace('.', '/'), "<group>");
            }
            return redacted;
        }

        public String normalizePath(String path) {
            if (path == null) {
                return "";
            }
            String normalized = path.replace('\\', '/');
            while (normalized.startsWith("./")) {
                normalized = normalized.substring(2);
            }
            return normalized;
        }

        public Set<String> redactedGroupIds() {
            return Set.copyOf(groupIds);
        }

        public String redactedPackagePrefix() {
            return packagePrefix;
        }
    }

    /** Names of the four archive entries, in order. Exposed for tests and the issue template. */
    public static List<String> entryNames() {
        return ENTRY_NAMES;
    }
}
