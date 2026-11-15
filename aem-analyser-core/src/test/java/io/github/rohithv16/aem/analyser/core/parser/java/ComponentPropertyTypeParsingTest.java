package io.github.rohithv16.aem.analyser.core.parser.java;

import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiOcdNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentPropertyTypeParsingTest {

    @TempDir
    Path root;

    @Test
    void componentPropertyTypeAnnotationBecomesAnOcd() throws Exception {
        Path file = root.resolve("EndpointConfig.java");
        Files.writeString(file, """
                package com.example;
                import org.osgi.service.component.annotations.ComponentPropertyType;
                @ComponentPropertyType
                public @interface EndpointConfig {
                    String endpoint();
                    int retries();
                }
                """);

        JavaSourceParseResult result = new JavaSourceParser().parse(file);
        List<OSGiOcdNode> ocds = result.ocds();

        assertEquals(1, ocds.size());
        OSGiOcdNode ocd = ocds.get(0);
        assertEquals("com.example.EndpointConfig", ocd.getOcdFqn());
        assertTrue(ocd.getPropertyMethods().containsKey("endpoint"), ocd.getPropertyMethods()::toString);
        assertTrue(ocd.getPropertyMethods().containsKey("retries"), ocd.getPropertyMethods()::toString);
    }
}
