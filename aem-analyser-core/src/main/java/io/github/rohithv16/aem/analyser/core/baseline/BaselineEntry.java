package io.github.rohithv16.aem.analyser.core.baseline;

/**
 * One accepted finding in a baseline. The descriptive fields make the file reviewable by humans and
 * let a reviewer understand what was accepted without re-running the analyser.
 */
public record BaselineEntry(String fingerprint, String ruleId, String filePath, String subtype) {
}
