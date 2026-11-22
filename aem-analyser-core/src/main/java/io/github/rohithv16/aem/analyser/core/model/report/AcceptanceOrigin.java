package io.github.rohithv16.aem.analyser.core.model.report;

/**
 * Why a finding is accepted rather than reported as actionable.
 *
 * <p>An accepted finding stays in the report (see {@link AnalysisReport#acceptedViolations()}) so
 * every exporter can show "36 exist, 36 accepted" instead of deleting the evidence. It is still
 * excluded from {@link AnalysisReport#violations()}, which is what the gate, the console summary and
 * the exit code count, so accepting a finding changes the artefact, never the verdict.
 */
public enum AcceptanceOrigin {

    /** Not accepted: the finding counts against the gate. */
    NONE,

    /** Accepted by an entry in the project's baseline file. */
    BASELINE,

    /** Accepted by an {@code aem-analyser-disable-*} directive in the source file itself. */
    INLINE;

    public boolean isAccepted() {
        return this != NONE;
    }
}
