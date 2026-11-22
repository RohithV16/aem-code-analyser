package io.github.rohithv16.aem.analyser.core.interop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.rules.RuleCatalog;
import io.github.rohithv16.aem.analyser.core.rules.metadata.RuleRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An explicit mapping from a foreign rule id to one of our rule ids.
 *
 * <p>The map is the whole safety mechanism: a foreign result never suppresses anything by location
 * alone. If {@code java:S3649} is not mapped to {@code AEM-QUAL-004}, a finding at the same line
 * stays actionable and the run still fails, so a wrong or missing map cannot hide our findings.
 */
public final class ImportMap {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, String> mapping;

    private ImportMap(Map<String, String> mapping) {
        this.mapping = Collections.unmodifiableMap(new LinkedHashMap<>(mapping));
    }

    public static ImportMap empty() {
        return new ImportMap(Map.of());
    }

    public static ImportMap of(Map<String, String> mapping) {
        return new ImportMap(mapping);
    }

    /**
     * Loads a YAML or JSON object of {@code foreign-id: OUR-ID} pairs.
     *
     * @throws IOException              unreadable or malformed file
     * @throws IllegalArgumentException a target that is not one of our rule ids
     */
    public static ImportMap load(Path file) throws IOException {
        JsonNode root;
        if (isYaml(file)) {
            root = YAML.readTree(Files.newBufferedReader(file));
        } else {
            root = JSON.readTree(Files.newBufferedReader(file));
        }
        if (root == null || !root.isObject()) {
            throw new IOException("Import map must be an object of foreign-rule: AEM-RULE pairs: " + file);
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            String foreign = entry.getKey() == null ? "" : entry.getKey().strip();
            String target = entry.getValue() == null ? "" : entry.getValue().asText("").strip();
            if (foreign.isEmpty()) {
                throw new IllegalArgumentException("Import map has an empty foreign rule id: " + file);
            }
            if (target.isEmpty()) {
                throw new IllegalArgumentException("Import map entry '" + foreign
                        + "' has no AEM rule id: " + file);
            }
            mapping.put(foreign, canonicalTarget(foreign, target));
        });
        if (mapping.isEmpty()) {
            throw new IOException("Import map is empty; remove --import-map to import for coverage only: " + file);
        }
        return new ImportMap(mapping);
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    /**
     * Resolves a mapped target to an id that actually produces findings. An alias resolves to its
     * replacement; an unknown or retired-without-replacement id is rejected with the offending entry
     * named, because silently dropping it would make the map look effective while it suppresses
     * nothing.
     */
    private static String canonicalTarget(String foreign, String target) {
        RuleId rule;
        try {
            rule = RuleId.fromId(target.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Import map entry '" + foreign + "' names unknown AEM rule '"
                    + target + "'. Run --list-rules to see the ids.");
        }
        if (rule.isRetired()) {
            String replacement = rule.replacedBy().orElseThrow(() -> new IllegalArgumentException(
                    "Import map entry '" + foreign + "' names retired rule '" + rule.id()
                            + "', which has no replacement"));
            return replacement;
        }
        String canonical = RuleCatalog.resolve(rule.id());
        if (RuleRegistry.find(canonical).isEmpty()) {
            throw new IllegalArgumentException("Import map entry '" + foreign + "' resolves to unknown rule '"
                    + canonical + "'");
        }
        return canonical;
    }

    /** @return our canonical rule id for a foreign rule, empty when the map does not name it */
    public java.util.Optional<String> targetFor(String foreignRuleId) {
        if (foreignRuleId == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(mapping.get(foreignRuleId.strip()));
    }

    public boolean isEmpty() {
        return mapping.isEmpty();
    }

    public Map<String, String> mapping() {
        return mapping;
    }
}
