package io.github.rohithv16.aem.analyser.core.baseline;

import java.util.List;

/**
 * Persisted set of accepted findings.
 *
 * @param schemaVersion baseline format version (bump when the fingerprint algorithm changes)
 * @param toolVersion   analyser version that produced the baseline
 * @param entries       accepted findings
 */
public record Baseline(int schemaVersion, String toolVersion, List<BaselineEntry> entries) {

    /**
     * Bumped in v2 when the fingerprint algorithm changed (verbatim message instead of a normalised
     * one, see {@link Fingerprint}). Old baselines are rejected loudly by {@link BaselineStore#load}
     * rather than silently failing to match and re-reporting every accepted finding.
     */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public Baseline {
        entries = List.copyOf(entries == null ? List.of() : entries);
    }

    public boolean contains(String fingerprint) {
        return entries.stream().anyMatch(entry -> entry.fingerprint().equals(fingerprint));
    }
}
