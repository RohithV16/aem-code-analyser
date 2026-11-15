package io.github.aemtools.analyser.core.parser.java;

import java.nio.file.Path;

/**
 * Pending {@code ADAPTS_TO} reference: Java code invokes {@code adaptTo(Target.class)}
 * on a Sling Model candidate type.
 */
public record PendingAdaptTo(String sourceClassFqn, String targetTypeName, Path filePath) {
}
