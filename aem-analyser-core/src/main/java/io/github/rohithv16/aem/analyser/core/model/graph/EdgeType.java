package io.github.rohithv16.aem.analyser.core.model.graph;

/**
 * Strongly-typed edge taxonomy of the cross-boundary reference graph.
 */
public enum EdgeType {
    /** OSGiConfigNode -> OSGiComponentNode */
    CONFIGURES,
    /** OSGiComponentNode -> OSGiOcdNode */
    DESIGNATES_OCD,
    /** OSGiOcdNode -> OcdPropertyMethodNode */
    DEFINES_PROPERTY,
    /** ConfigPropertyNode -> OcdPropertyMethodNode */
    BINDS_PROPERTY,
    /** OSGiConfigNode -> ConfigPropertyNode */
    CONTAINS_PROPERTY,
    /** OSGiComponentNode -> OcdPropertyMethodNode (direct call / field access) */
    CONSUMES_PROPERTY,
    /** OSGiComponentNode | SlingModelNode -> OSGiComponentNode */
    INJECTS_SERVICE,
    /** JavaClassNode -> SlingModelNode */
    ADAPTS_TO,
    /** JavaClassNode -> JavaClassNode */
    REFERENCES_CLASS
}
