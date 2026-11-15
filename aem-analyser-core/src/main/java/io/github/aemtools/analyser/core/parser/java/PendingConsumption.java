package io.github.aemtools.analyser.core.parser.java;

import io.github.aemtools.analyser.core.model.graph.SourceLocation;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Pending, unresolved consumption of an OCD {@code @AttributeDefinition} method observed
 * inside a Declarative Services component body. Resolved during graph edge linkage.
 */
public record PendingConsumption(
        String componentFqn,
        String ocdTypeName,
        String methodName,
        Path filePath,
        SourceLocation callSite,
        Optional<String> invocationTypeName,
        String enclosingMethodName) {

    public enum InvocationType {
        DIRECT_METHOD_CALL,
        FIELD_MEMBER_ACCESS,
        CONSTRUCTOR_INJECTION,
        COMPONENT_PROPERTY_TYPE;

        public static Optional<InvocationType> from(String name) {
            try {
                return Optional.of(valueOf(name));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }
}
