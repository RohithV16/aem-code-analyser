package io.github.rohithv16.aem.analyser.core.interop;

/**
 * One result read from another analyser's SARIF document.
 *
 * @param tool      tool name from {@code runs[].tool.driver.name}, or {@code "external"}
 * @param ruleId    the foreign rule id exactly as the other tool wrote it (e.g. {@code java:S3649})
 * @param uri       reactor-relative path, decoded and '/'-separated
 * @param startLine 1-based first line
 * @param endLine   1-based last line (equal to {@code startLine} when the tool omitted it)
 */
public record ForeignFinding(String tool, String ruleId, String uri, int startLine, int endLine) {

    public ForeignFinding {
        tool = tool == null || tool.isBlank() ? "external" : tool.strip();
        ruleId = ruleId == null ? "" : ruleId.strip();
        uri = uri == null ? "" : uri;
        startLine = Math.max(0, startLine);
        endLine = Math.max(startLine, endLine);
    }

    /** True when this foreign result and a finding at {@code line..line} share a line. */
    public boolean overlaps(int findingStart, int findingEnd) {
        if (startLine <= 0 || findingStart <= 0) {
            return false;
        }
        return startLine <= Math.max(findingStart, findingEnd)
                && Math.max(startLine, endLine) >= findingStart;
    }

    /** Provenance recorded on the suppression, e.g. {@code sonar java:S3649 @ ui.apps/x.html:41}. */
    public String justification() {
        return tool + " " + ruleId + " @ " + uri + ":" + startLine;
    }
}
