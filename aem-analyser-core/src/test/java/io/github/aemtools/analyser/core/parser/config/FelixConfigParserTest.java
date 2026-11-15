package io.github.aemtools.analyser.core.parser.config;

import io.github.aemtools.analyser.core.model.graph.DataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FelixConfigParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesTypedFelixSyntax() throws Exception {
        Path file = tempDir.resolve("com.example.Felix.config");
        Files.writeString(file, """
                # Comment line
                port=I"8080"
                name=S"hello world"
                threshold=L"1000000"
                ratio=D"0.75"
                enabled=B"true"
                tags=[I"1",I"2",I"3"]
                """);
        Map<String, ConfigPropertyEntry> properties = new FelixConfigParser().parse(file);
        assertEquals(DataType.INTEGER, properties.get("port").inferredType());
        assertEquals("8080", properties.get("port").rawValue());
        assertEquals(DataType.STRING, properties.get("name").inferredType());
        assertEquals("hello world", properties.get("name").rawValue());
        assertEquals(DataType.LONG, properties.get("threshold").inferredType());
        assertEquals(DataType.DOUBLE, properties.get("ratio").inferredType());
        assertEquals(DataType.BOOLEAN, properties.get("enabled").inferredType());
        assertEquals(DataType.ARRAY, properties.get("tags").inferredType());
        assertEquals("[1, 2, 3]", properties.get("tags").rawValue());
    }

    @Test
    void handlesEscapesAndLineContinuation() throws Exception {
        Path file = tempDir.resolve("com.example.Escaped.config");
        Files.writeString(file, """
                user.mapping=["ocs\\=com.example.osgi;[logs] = service-user"]
                note="line one \\
                line two"
                """);
        Map<String, ConfigPropertyEntry> properties = new FelixConfigParser().parse(file);
        assertEquals("ocs=com.example.osgi;[logs] = service-user", properties.get("user.mapping").rawValue());
        assertEquals("line one line two", properties.get("note").rawValue());
    }

    @Test
    void parsesEnvPlaceholders() throws Exception {
        Path file = tempDir.resolve("com.example.Placeholder.config");
        Files.writeString(file, "apiKey=$[secret:API_KEY]\n");
        Map<String, ConfigPropertyEntry> properties = new FelixConfigParser().parse(file);
        assertEquals("$[secret:API_KEY]", properties.get("apiKey").rawValue());
        assertEquals(true, properties.get("apiKey").isEnvPlaceholder());
    }
}
