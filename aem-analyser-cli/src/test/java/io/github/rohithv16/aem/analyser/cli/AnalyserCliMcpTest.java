package io.github.rohithv16.aem.analyser.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --mcp} is a transport switch: stdout carries JSON-RPC frames and nothing else, the CLI's
 * usual progress chatter moves to stderr, and EOF ends the session with exit code 0.
 */
class AnalyserCliMcpTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private record McpRun(int exitCode, String stdout, String stderr) {

        List<JsonNode> frames() throws Exception {
            List<JsonNode> frames = new ArrayList<>();
            for (String line : stdout.split("\n")) {
                if (!line.isBlank()) {
                    frames.add(new ObjectMapper().readTree(line));
                }
            }
            return frames;
        }
    }

    /** Runs the CLI in-process with scripted stdin, capturing both streams. */
    private McpRun run(Path project, String stdin, String... extraOptions) {
        String[] args = new String[extraOptions.length + 2];
        args[0] = project.toString();
        args[1] = "--mcp";
        System.arraycopy(extraOptions, 0, args, 2, extraOptions.length);

        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(capturedOut, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(capturedErr, true, StandardCharsets.UTF_8)) {
            System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            System.setOut(out);
            System.setErr(err);
            int exit = new CommandLine(new AnalyserCli()).execute(args);
            return new McpRun(exit, capturedOut.toString(StandardCharsets.UTF_8),
                    capturedErr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    void mcpServesProtocolFramesOnStdoutAndLogsOnStderr(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        String stdin = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"probe\",\"version\":\"0\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"explain_rule\",\"arguments\":{\"id\":\"AEM-DEAD-004\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"explain_rule\",\"arguments\":{\"id\":\"AEM-NOPE-999\"}}}",
                "not even json");

        McpRun run = run(project, stdin, "--no-sonar");

        assertEquals(0, run.exitCode(), run.stderr());
        List<JsonNode> frames = run.frames();
        assertEquals(5, frames.size(), "one frame per request, none for the notification:\n" + run.stdout());

        assertEquals("2025-06-18", frames.get(0).path("result").path("protocolVersion").asText());
        assertEquals(4, frames.get(1).path("result").path("tools").size());
        assertEquals("AEM-DEAD-004", frames.get(2).path("result")
                .path("structuredContent").path("id").asText());
        assertEquals(-32602, frames.get(3).path("error").path("code").asInt());
        assertTrue(frames.get(3).path("error").path("message").asText().contains("Unknown rule"));
        assertEquals(-32700, frames.get(4).path("error").path("code").asInt());
        assertTrue(frames.get(4).path("id").isNull());

        assertTrue(run.stderr().contains("MCP server on stdio"),
                "the operator-facing startup line belongs on stderr:\n" + run.stderr());
        assertFalse(run.stdout().contains("[INFO]"), "no log line may reach the protocol stream");
        assertFalse(run.stdout().contains("at io.github"), "no stack trace may reach stdout");
    }

    @Test
    void quietStillWritesFramesAndSuppressesTheStartupLine(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        McpRun run = run(project, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n", "-q");

        assertEquals(0, run.exitCode());
        assertEquals(1, run.frames().size());
        assertTrue(run.frames().get(0).path("result").isObject());
        assertFalse(run.stderr().contains("[INFO]"), run.stderr());
    }

    @Test
    void eofOnStdinIsANormalShutdown(@TempDir Path project) throws Exception {
        CliTestSupport.writeMinimalProject(project);
        McpRun run = run(project, "");

        assertEquals(0, run.exitCode(), "--mcp exits 0 when the client closes stdin");
        assertEquals("", run.stdout(), "no frames are invented when there were no requests");
    }
}
