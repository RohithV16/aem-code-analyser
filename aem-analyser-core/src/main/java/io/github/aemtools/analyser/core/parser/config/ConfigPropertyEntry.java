package io.github.aemtools.analyser.core.parser.config;

import io.github.aemtools.analyser.core.model.graph.DataType;

/**
 * A single typed property entry inside an OSGi configuration file.
 *
 * @param baseKey           normalized semantic key (RFC 724 type hint stripped)
 * @param rawKey            verbatim key as written in the file
 * @param typeHint          optional RFC 724 type hint (Integer, String[], ...)
 * @param rawValue          string representation of the value
 * @param inferredType      inferred data type
 * @param isEnvPlaceholder  true when the value is a $[env:...] / $[secret:...] placeholder
 * @param line              1-based line of the key in the file
 * @param column            1-based column of the key in the file
 */
public record ConfigPropertyEntry(
        String baseKey,
        String rawKey,
        String typeHint,
        String rawValue,
        DataType inferredType,
        boolean isEnvPlaceholder,
        int line,
        int column) {
}
