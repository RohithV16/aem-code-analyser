package io.github.rohithv16.aem.analyser.core.parser.java;

import java.nio.file.Path;

/**
 * Pending {@code INJECTS_SERVICE} dependency: a component or Sling Model references
 * another type by name (via {@code @Reference}, {@code @OSGiService} or field usage).
 */
public record PendingServiceInjection(String consumerFqn, String serviceTypeName,
                                      boolean consumerIsSlingModel, Path filePath) {
}
