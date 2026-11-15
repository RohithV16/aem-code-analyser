package io.github.aemtools.analyser.core.model.graph;

/**
 * Strongly-typed node taxonomy of the cross-boundary reference graph.
 */
public enum NodeType {
    OSGI_CONFIG,
    CONFIG_PROPERTY,
    JAVA_CLASS,
    OSGI_COMPONENT,
    OSGI_OCD,
    OCD_PROPERTY_METHOD,
    SLING_MODEL
}
