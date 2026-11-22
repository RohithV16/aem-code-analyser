package io.github.rohithv16.aem.analyser.core.model.report;

/**
 * Lifecycle state of a rule id.
 *
 * <p>The status is part of the public contract: a configuration, inline directive or baseline may
 * name an id for years after it stopped being the preferred spelling. {@link #DEPRECATED} ids still
 * produce findings, {@link #RETIRED} ids do not and must name the rule that replaced them.
 */
public enum RuleStatus {

    /** Current spelling; the rule may produce findings. */
    ACTIVE("active"),

    /** Still produces findings, but new configurations should use {@code replacedBy}. */
    DEPRECATED("deprecated"),

    /** No producer. Kept so old configuration, baselines and SARIF consumers resolve. */
    RETIRED("retired");

    private final String wireName;

    RuleStatus(String wireName) {
        this.wireName = wireName;
    }

    /** Lower-case name used in {@code --list-rules --format json} and {@code rule-evidence.json}. */
    public String wireName() {
        return wireName;
    }

    /** True when the id is kept only for compatibility and produces no new findings. */
    public boolean isRetired() {
        return this == RETIRED;
    }
}
