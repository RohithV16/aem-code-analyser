package io.github.rohithv16.aem.analyser.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rohithv16.aem.analyser.core.config.AnalyserConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protocol contract of {@link McpServer}: one JSON object per stdout line and nothing else,
 * four advertised tools, registry metadata for live ids, JSON-RPC errors for unknown ids/tools/
 * methods and malformed JSON — with the session still serving afterwards.
 */
class McpServerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path project;

    /** A project whose only finding is one AEM-QUAL-003 warning (the scheduler whiteboard). */
    private void writeSchedulerProject() throws IOException {
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId>"
                        + "<artifactId>fixture</artifactId><version>1.0.0</version>"
                        + "<packaging>pom</packaging><modules><module>core</module></modules></project>");
        Path core = Files.createDirectories(project.resolve("core"));
        Files.writeString(core.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<parent><groupId>com.acme</groupId><artifactId>fixture</artifactId>"
                        + "<version>1.0.0</version></parent>"
                        + "<artifactId>fixture.core</artifactId><packaging>bundle</packaging></project>");
        Path sources = Files.createDirectories(core.resolve("src/main/java/com/acme"));
        Files.writeString(sources.resolve("Poller.java"),
                "package com.acme;\n"
                        + "import org.apache.sling.commons.scheduler.Scheduler;\n"
                        + "import org.osgi.service.component.annotations.Component;\n"
                        + "import org.osgi.service.component.annotations.Reference;\n"
                        + "@Component(service = Runnable.class)\n"
                        + "public class Poller implements Runnable {\n"
                        + "    @Reference\n"
                        + "    private Scheduler scheduler;\n"
                        + "    @Override\n"
                        + "    public void run() {\n"
                        + "    }\n"
                        + "}\n");
    }

    /** Runs one server over {@code input} and returns the raw stdout frame text. */
    private String serve(String input) throws IOException {
        StringWriter stdout = new StringWriter();
        McpTools tools = new McpTools(project, AnalyserConfig.empty(), false, false, line -> { }, mapper);
        McpServer server = new McpServer(new StringReader(input), stdout, mapper, tools, line -> { });
        assertEquals(0, server.serve(), "EOF on stdin is a normal shutdown");
        return stdout.toString();
    }

    /** Every line must be a complete JSON object; a stray log line fails here, as it would in a client. */
    private List<JsonNode> frames(String stdout) {
        if (stdout.isEmpty()) {
            return List.of();
        }
        assertTrue(stdout.endsWith("\n"), "every frame is line-terminated:\n" + stdout);
        List<JsonNode> frames = new ArrayList<>();
        for (String line : stdout.split("\n")) {
            assertFalse(line.isBlank(), "no blank frame lines");
            try {
                frames.add(mapper.readTree(line));
            } catch (IOException e) {
                throw new AssertionError("stdout line is not JSON: " + line, e);
            }
        }
        return frames;
    }

    private static String initialize(int id, String version) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"" + version + "\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"probe\",\"version\":\"0\"}}}";
    }

    private static String toolCall(int id, String tool, String argumentsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"" + tool + "\",\"arguments\":" + argumentsJson + "}}";
    }

    private static String stringArray(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(node -> values.add(node.asText()));
        return String.join(",", values);
    }

    @Test
    void initializeNegotiatesTheProtocolAndListsFourTools() throws IOException {
        String stdout = serve(String.join("\n",
                initialize(1, "2025-06-18"),
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));

        List<JsonNode> frames = frames(stdout);
        assertEquals(2, frames.size(), "the initialized notification has no response:\n" + stdout);

        JsonNode init = frames.get(0).path("result");
        assertEquals("2025-06-18", init.path("protocolVersion").asText());
        assertFalse(init.path("capabilities").path("tools").path("listChanged").asBoolean(true));
        assertEquals("aem-code-analyser", init.path("serverInfo").path("name").asText());
        assertFalse(init.path("serverInfo").path("version").asText().isBlank());
        assertFalse(init.path("instructions").asText().isBlank());

        JsonNode tools = frames.get(1).path("result").path("tools");
        assertEquals(4, tools.size(), "four tools are advertised");
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> {
            names.add(tool.path("name").asText());
            assertEquals("object", tool.path("inputSchema").path("type").asText(),
                    tool.path("name").asText() + " must declare an object input schema");
            assertFalse(tool.path("description").asText().isBlank());
        });
        assertEquals(List.of("analyse", "list_rules", "explain_rule", "explain_finding"), names,
                "advertisement order is stable");
    }

    @Test
    void initializeEchoesAKnownRevisionAndFallsBackForAnUnknownOne() throws IOException {
        List<JsonNode> frames = frames(serve(String.join("\n",
                initialize(1, "2024-11-05"),
                initialize(2, "1999-01-01"))));

        assertEquals(2, frames.size());
        assertEquals("2024-11-05", frames.get(0).path("result").path("protocolVersion").asText());
        assertEquals(McpServer.PROTOCOL_VERSION,
                frames.get(1).path("result").path("protocolVersion").asText(),
                "an unknown revision gets the server's latest, per the MCP handshake");
    }

    @Test
    void explainRuleReturnsTheLiveRegistryMetadata() throws IOException {
        List<JsonNode> frames = frames(serve(toolCall(3, "explain_rule", "{\"id\":\"AEM-DEAD-004\"}")));

        JsonNode result = frames.get(0).path("result");
        JsonNode rule = result.path("structuredContent");
        assertEquals("AEM-DEAD-004", rule.path("id").asText());
        assertTrue(rule.path("remediation").asText().contains("data-sly-use"), rule.toString());
        assertTrue(rule.path("fullDescription").asText().length() > 20, rule.toString());
        assertTrue(rule.path("helpUri").asText().startsWith("https://"), rule.toString());
        assertTrue(stringArray(rule.path("tags")).contains("htl"), rule.toString());
        assertFalse(rule.path("severity").asText().isBlank());

        // The text content a non-structured client reads must be the same JSON document.
        JsonNode textPayload = mapper.readTree(result.path("content").get(0).path("text").asText());
        assertEquals(rule, textPayload);
    }

    @Test
    void anUnknownRuleIsAJsonRpcErrorAndTheSessionSurvives() throws IOException {
        String stdout = serve(String.join("\n",
                toolCall(10, "explain_rule", "{\"id\":\"AEM-NOPE-999\"}"),
                "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\"}"));

        List<JsonNode> frames = frames(stdout);
        assertEquals(2, frames.size(), "an error response does not end the loop:\n" + stdout);
        assertEquals(McpServer.INVALID_PARAMS, frames.get(0).path("error").path("code").asInt());
        assertTrue(frames.get(0).path("error").path("message").asText().contains("Unknown rule"),
                frames.get(0).toString());
        assertEquals(10, frames.get(0).path("id").asInt(), "the request id is echoed");
        assertFalse(stdout.contains("at io.github"), "a stack trace must never reach stdout:\n" + stdout);
        assertFalse(stdout.contains("\tat "), "a stack trace must never reach stdout:\n" + stdout);

        assertEquals(4, frames.get(1).path("result").path("tools").size());
    }

    @Test
    void malformedJsonIsAParseErrorAndTheSessionSurvives() throws IOException {
        String stdout = serve(String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"));

        List<JsonNode> frames = frames(stdout);
        assertEquals(2, frames.size(), stdout);
        assertEquals(McpServer.PARSE_ERROR, frames.get(0).path("error").path("code").asInt());
        assertTrue(frames.get(0).path("id").isNull(), "a parse error has no id to echo");
        assertEquals(2, frames.get(1).path("id").asInt());
        assertTrue(frames.get(1).path("result").isObject(), "the server still answers after a bad frame");
    }

    @Test
    void unknownMethodsToolsAndMissingArgumentsAreJsonRpcErrors() throws IOException {
        List<JsonNode> frames = frames(serve(String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/deploy\"}",
                toolCall(2, "deploy", "{}"),
                toolCall(3, "explain_rule", "{}"),
                toolCall(4, "list_rules", "{\"tag\":42}"))));

        assertEquals(4, frames.size());
        assertEquals(McpServer.METHOD_NOT_FOUND, frames.get(0).path("error").path("code").asInt());
        assertEquals(McpServer.INVALID_PARAMS, frames.get(1).path("error").path("code").asInt());
        assertTrue(frames.get(1).path("error").path("message").asText().contains("analyse"),
                "an unknown tool names the available ones");
        assertEquals(McpServer.INVALID_PARAMS, frames.get(2).path("error").path("code").asInt());
        assertEquals(McpServer.INVALID_PARAMS, frames.get(3).path("error").path("code").asInt());
    }

    @Test
    void listRulesFiltersByTagAndRejectsAnUnknownOne() throws IOException {
        List<JsonNode> frames = frames(serve(String.join("\n",
                toolCall(1, "list_rules", "{\"tag\":\"security\"}"),
                toolCall(2, "list_rules", "{\"tag\":\"no-such-tag\"}"))));

        JsonNode payload = frames.get(0).path("result").path("structuredContent");
        assertEquals("security", payload.path("tag").asText());
        assertTrue(payload.path("count").asInt() >= 2, payload.toString());
        payload.path("rules").forEach(rule -> assertTrue(stringArray(rule.path("tags")).contains("security"),
                rule.toString()));

        assertEquals(McpServer.INVALID_PARAMS, frames.get(1).path("error").path("code").asInt());
        assertTrue(frames.get(1).path("error").path("message").asText().contains("Known tags"),
                frames.get(1).toString());
    }

    @Test
    void analyseReturnsCompactFindingsAndExplainFindingResolvesAFingerprint() throws IOException {
        writeSchedulerProject();

        List<JsonNode> analysed = frames(serve(toolCall(1, "analyse",
                "{\"root\":\"" + project.toString().replace("\\", "\\\\") + "\"}")));
        JsonNode payload = analysed.get(0).path("result").path("structuredContent");
        assertEquals(project.toAbsolutePath().normalize().toString(), payload.path("root").asText());
        assertEquals(1, payload.path("summary").path("total").asInt(), payload.toString());
        assertEquals(1, payload.path("summary").path("warning").asInt(), payload.toString());

        JsonNode finding = payload.path("findings").get(0);
        assertEquals("AEM-QUAL-003", finding.path("ruleId").asText());
        assertEquals("WARNING", finding.path("severity").asText());
        assertEquals("core/src/main/java/com/acme/Poller.java", finding.path("file").asText());
        assertTrue(finding.path("line").asInt() >= 1, finding.toString());
        assertFalse(finding.path("message").asText().isBlank());
        assertFalse(finding.path("remediation").asText().isBlank());
        String fingerprint = finding.path("fingerprint").asText();
        assertEquals(16, fingerprint.length(), "the baseline fingerprint is 16 hex characters");

        // A fresh session with no analyse call: explain_finding analyses the default root lazily.
        List<JsonNode> explained = frames(serve(toolCall(2, "explain_finding",
                "{\"fingerprint\":\"" + fingerprint + "\"}")));
        JsonNode explainedPayload = explained.get(0).path("result").path("structuredContent");
        assertEquals(fingerprint, explainedPayload.path("finding").path("fingerprint").asText());
        assertEquals("AEM-QUAL-003", explainedPayload.path("finding").path("ruleId").asText());
        assertEquals("AEM-QUAL-003", explainedPayload.path("rule").path("id").asText());
        assertFalse(explainedPayload.path("rule").path("remediation").asText().isBlank());
    }

    @Test
    void unknownFingerprintIsAJsonRpcError() throws IOException {
        writeSchedulerProject();

        List<JsonNode> frames = frames(serve(toolCall(1, "explain_finding",
                "{\"fingerprint\":\"0000000000000000\"}")));

        assertNotNull(frames.get(0).get("error"), frames.get(0).toString());
        assertEquals(McpServer.INVALID_PARAMS, frames.get(0).path("error").path("code").asInt());
        assertTrue(frames.get(0).path("error").path("message").asText().contains("Call analyse"),
                frames.get(0).toString());
    }

    @Test
    void aMissingRootIsAToolErrorNotACrash() throws IOException {
        List<JsonNode> frames = frames(serve(toolCall(1, "analyse", "{\"root\":\"/no/such/aem/project\"}")));

        assertEquals(McpServer.INVALID_PARAMS, frames.get(0).path("error").path("code").asInt());
        assertTrue(frames.get(0).path("error").path("message").asText().contains("not a readable directory"),
                frames.get(0).toString());
    }

    @Test
    void explainRuleDescribesAnEmbeddedSonarIdInsteadOfCallingItUnknown() throws IOException {
        List<JsonNode> frames = frames(serve(toolCall(1, "explain_rule", "{\"id\":\"java:S106\"}")));

        JsonNode rule = frames.get(0).path("result").path("structuredContent");
        assertEquals("java:S106", rule.path("id").asText());
        assertFalse(rule.path("registered").asBoolean(true),
                "SonarJava ids are not AEM-registry rules");
        assertTrue(rule.path("helpUri").asText().contains("sonarsource.com"), rule.toString());
    }

    @Test
    void stringRequestIdsAreEchoedVerbatim() throws IOException {
        List<JsonNode> frames = frames(serve("{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"method\":\"ping\"}"));

        assertEquals(1, frames.size());
        assertTrue(frames.get(0).path("id").isTextual(), "a string id stays a string");
        assertEquals("req-1", frames.get(0).path("id").asText());
    }

    @Test
    void blankLinesAndNotificationsProduceNoFrames() throws IOException {
        assertEquals("", serve("\n\n{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"));
    }
}
