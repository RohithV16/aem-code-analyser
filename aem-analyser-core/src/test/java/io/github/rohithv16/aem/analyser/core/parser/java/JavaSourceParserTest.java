package io.github.rohithv16.aem.analyser.core.parser.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for Java language features that must not silently drop a file. */
class JavaSourceParserTest {

    @TempDir
    Path root;

    @Test
    void recordPatternsWithVarAreParsedInsteadOfSkipped() throws Exception {
        Path file = root.resolve("OutcomeFormatter.java");
        Files.writeString(file, """
                package com.example;

                public class OutcomeFormatter {

                    sealed interface Outcome permits Ok, Err {}

                    record Ok(String value) implements Outcome {}

                    record Err(String reason) implements Outcome {}

                    String describe(Outcome outcome) {
                        return switch (outcome) {
                            case Ok(var value) -> value;
                            case Err(var reason) -> reason;
                        };
                    }
                }
                """);

        // parseStrict throws on any parse problem, which is what the orchestrator turns into a
        // JAVA_PARSE_ERROR diagnostic. Java 21 record patterns with `var` must parse cleanly.
        JavaSourceParseResult result = assertDoesNotThrow(
                () -> new JavaSourceParser().parseStrict(file));

        assertTrue(result.javaClasses().stream()
                        .anyMatch(clazz -> clazz.getFqn().equals("com.example.OutcomeFormatter")),
                () -> "Expected the enclosing class to be parsed: " + result.javaClasses());
    }
}
