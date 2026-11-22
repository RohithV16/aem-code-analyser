package io.github.rohithv16.aem.analyser.core.model.report;

/**
 * The outcome of the quality gate for one run, computed once from the <em>effective</em> gate.
 *
 * <p>Rendering and the process exit code must both read this value: deriving the headline from the
 * error count alone is what made {@code --fail-on NONE} print "GATE FAILED" while exiting 0, and
 * what let a coverage/duplication threshold fail silently in the verdict line.
 *
 * @param status what the gate decided
 * @param reason the human-readable justification, without the {@code "GATE ...:"} prefix
 */
public record GateVerdict(Status status, String reason) {

    /** Gate outcomes, ordered from best to worst. */
    public enum Status {
        /** No findings at all. */
        PASSED,
        /** Findings exist but none reach the effective failure threshold. */
        PASSED_WITH_WARNINGS,
        /** Findings exist but the run is report-only ({@code --fail-on NONE}): not a pass, not a failure. */
        NOT_ENFORCED,
        /** At least one configured gate condition tripped. */
        FAILED
    }

    public GateVerdict {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        reason = reason == null || reason.isBlank() ? "no reason recorded." : reason;
    }

    public static GateVerdict passed() {
        return new GateVerdict(Status.PASSED, "no cross-boundary dead code detected.");
    }

    public static GateVerdict passedWithWarnings(String reason) {
        return new GateVerdict(Status.PASSED_WITH_WARNINGS, reason);
    }

    public static GateVerdict notEnforced(String reason) {
        return new GateVerdict(Status.NOT_ENFORCED, reason);
    }

    public static GateVerdict failed(String reason) {
        return new GateVerdict(Status.FAILED, reason);
    }

    /**
     * The verdict implied by the findings alone. Used as the default for reports built by callers
     * that have no gate configuration (exporters, tests, the Maven plugin); the CLI replaces it with
     * the verdict of the effective gate.
     */
    public static GateVerdict forCounts(long errors, long warnings, long infos, boolean anyViolations) {
        if (!anyViolations) {
            return passed();
        }
        if (errors > 0) {
            return failed(errors + " error-severity finding" + (errors == 1 ? "" : "s") + " detected.");
        }
        return passedWithWarnings(warnings + " warning(s), " + infos + " info message(s).");
    }

    public boolean failed() {
        return status == Status.FAILED;
    }

    /** The process exit code this verdict implies: 1 for a failed gate, 0 otherwise. */
    public int exitCode() {
        return failed() ? 1 : 0;
    }

    /** The verdict label, without the reason. */
    public String headline() {
        return switch (status) {
            case PASSED -> "GATE PASSED";
            case PASSED_WITH_WARNINGS -> "GATE PASSED WITH WARNINGS";
            case NOT_ENFORCED -> "GATE NOT ENFORCED";
            case FAILED -> "GATE FAILED";
        };
    }

    /** The single line every renderer prints, e.g. {@code GATE FAILED: 8 error-severity findings detected.} */
    public String render() {
        return headline() + ": " + reason;
    }
}
