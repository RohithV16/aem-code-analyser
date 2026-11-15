package io.github.rohithv16.aem.analyser.core.rules.metadata;

/**
 * SonarQube-compatible issue classification used by the Sonar exporter and CLI listings.
 */
public enum RuleType {
    BUG,
    VULNERABILITY,
    CODE_SMELL,
    SECURITY_HOTSPOT
}
