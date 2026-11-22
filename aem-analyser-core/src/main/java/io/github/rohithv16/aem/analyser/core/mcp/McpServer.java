package io.github.rohithv16.aem.analyser.core.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rohithv16.aem.analyser.core.config.AnalyserConfig;
import io.github.rohithv16.aem.analyser.core.export.VersionInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * A Model Context Protocol server on stdio (issue #57).
 *
 * <p>Transport contract: one JSON-RPC 2.0 object per line on stdout, and <em>nothing else</em> on
 * stdout — no banners, no progress, no stack traces. Anything an operator needs to see is written
 * through the {@link Consumer log} (stderr in the CLI), because a single stray line makes every MCP
 * client fail to parse the stream.
 *
 * <p>Implemented against the MCP {@value #PROTOCOL_VERSION} revision with the three methods a client
 * needs to discover and use tools ({@code initialize}, {@code tools/list}, {@code tools/call}) plus
 * {@code ping} and the {@code notifications/initialized} notification. Unknown methods, unknown tools,
 * malformed JSON and failing tools are answered with JSON-RPC error objects; a bad request never
 * terminates the loop, so one broken frame cannot take the session down.
 */
public final class McpServer {

    /** The protocol revision this server speaks, and its answer when the client asks for another. */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    /** Revisions whose framing this server implements; a known request is echoed back. */
    static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
            List.of(PROTOCOL_VERSION, "2025-03-26", "2024-11-05");

    public static final String SERVER_NAME = "aem-code-analyser";

    // JSON-RPC 2.0 error codes (https://www.jsonrpc.org/specification#error_object).
    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    private static final String INSTRUCTIONS = """
            AEM Code Analyser answers with the same findings, severities and remediation strings the \
            build gate uses. Start with analyse for a project root, then pass a finding's fingerprint \
            to explain_finding, or a finding's ruleId to explain_rule. Paths are relative to the \
            analysed root; list_rules shows the full catalogue and its tags.""";

    private final BufferedReader reader;
    private final Writer writer;
    private final ObjectMapper mapper;
    private final McpTools tools;
    private final Consumer<String> log;

    public McpServer(Reader in, Writer out, ObjectMapper mapper, McpTools tools, Consumer<String> log) {
        this.reader = in instanceof BufferedReader buffered ? buffered : new BufferedReader(in);
        this.writer = out;
        this.mapper = mapper;
        this.tools = tools;
        this.log = log == null ? line -> { } : log;
    }

    /**
     * Binds a server to the process's stdio. stdout is wrapped raw (not through {@code PrintWriter})
     * so every frame is flushed exactly once, after the line is complete.
     */
    public static McpServer stdio(Path projectRoot,
                                  AnalyserConfig config,
                                  boolean enableSonar,
                                  boolean enableCache,
                                  Consumer<String> log) {
        ObjectMapper mapper = new ObjectMapper();
        McpTools tools = new McpTools(projectRoot, config, enableSonar, enableCache, log, mapper);
        return new McpServer(new InputStreamReader(System.in, StandardCharsets.UTF_8),
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8), mapper, tools, log);
    }

    /**
     * Serves until stdin reaches EOF.
     *
     * @return 0: a closed stdin is the normal shutdown a client performs, not a failure
     */
    public int serve() throws IOException {
        log.accept("[INFO] mcp: serving " + tools.names().size() + " tools on stdio (protocol "
                + PROTOCOL_VERSION + ")");
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                // A blank line carries no request; answering it would emit a frame no client asked for.
                continue;
            }
            String frame = handleLine(line);
            if (frame != null) {
                writer.write(frame);
                writer.write('\n');
                writer.flush();
            }
        }
        log.accept("[INFO] mcp: stdin closed, exiting");
        return 0;
    }

    /** Parses and answers one line; {@code null} when the request was a notification. */
    String handleLine(String line) {
        JsonNode parsed;
        try {
            parsed = mapper.readTree(line);
        } catch (JsonProcessingException e) {
            return error(null, PARSE_ERROR, "Parse error: " + McpTools.oneLine(e.getOriginalMessage()));
        }
        if (parsed == null || !parsed.isObject()) {
            return error(null, INVALID_REQUEST,
                    "Invalid request: expected one JSON-RPC object per line (batches are not supported).");
        }
        return handle((ObjectNode) parsed);
    }

    private String handle(ObjectNode request) {
        JsonNode id = request.get("id");
        boolean notification = id == null;
        if (!"2.0".equals(request.path("jsonrpc").asText())) {
            return notification ? null : error(id, INVALID_REQUEST, "Invalid request: 'jsonrpc' must be \"2.0\".");
        }
        JsonNode methodNode = request.get("method");
        if (methodNode == null || !methodNode.isTextual()) {
            return notification ? null : error(id, INVALID_REQUEST, "Invalid request: 'method' must be a string.");
        }
        String method = methodNode.asText();
        if (notification) {
            // A request without an id is a notification: the client is not waiting for a frame, and
            // replying would emit one nobody asked for (MCP's notifications/initialized included).
            return null;
        }
        return switch (method) {
            case "initialize" -> success(id, initialize(request.path("params")));
            case "ping" -> success(id, mapper.createObjectNode());
            case "tools/list" -> success(id, toolsList());
            case "tools/call" -> toolsCall(id, request.get("params"));
            default -> notification ? null : error(id, METHOD_NOT_FOUND, "Method '" + method
                    + "' not found. Supported methods: initialize, ping, tools/list, tools/call.");
        };
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        String version = SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSION;
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", version);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", VersionInfo.current());
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode definitions = result.putArray("tools");
        definitions.addAll(tools.definitions());
        return result;
    }

    private String toolsCall(JsonNode id, JsonNode params) {
        if (params == null || !params.isObject()) {
            return error(id, INVALID_PARAMS, "Invalid params: tools/call requires a params object.");
        }
        JsonNode nameNode = params.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
            return error(id, INVALID_PARAMS, "Invalid params: tools/call requires a tool name.");
        }
        String name = nameNode.asText().strip();
        JsonNode arguments = params.get("arguments");
        if (arguments != null && !arguments.isNull() && !arguments.isObject()) {
            return error(id, INVALID_PARAMS, "Invalid params: 'arguments' must be an object.");
        }
        if (!tools.has(name)) {
            return error(id, INVALID_PARAMS, "Unknown tool '" + name + "'. Available tools: "
                    + String.join(", ", tools.names()) + ".");
        }
        try {
            JsonNode payload = tools.invoke(name, arguments);
            return success(id, toolResult(payload));
        } catch (McpTools.ToolException e) {
            return error(id, INVALID_PARAMS, e.getMessage());
        } catch (RuntimeException e) {
            // A tool bug is reported as a protocol error with one flat sentence: a stack trace on
            // stdout would corrupt the stream for every subsequent frame.
            log.accept("[ERROR] mcp: tool '" + name + "' failed: " + McpTools.oneLine(e.getMessage()));
            return error(id, INTERNAL_ERROR, "Tool '" + name + "' failed: " + McpTools.oneLine(e.getMessage()));
        }
    }

    private ObjectNode toolResult(JsonNode payload) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        try {
            text.put("text", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise a tool result", e);
        }
        result.set("structuredContent", payload);
        result.put("isError", false);
        return result;
    }

    private String success(JsonNode id, ObjectNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        set(response, id);
        response.set("result", result);
        return write(response);
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        set(response, id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", McpTools.oneLine(message));
        return write(response);
    }

    /** Echoes the request id exactly (number or string); absent/null becomes JSON {@code null}. */
    private void set(ObjectNode response, JsonNode id) {
        if (id == null || id.isNull()) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
    }

    private String write(ObjectNode response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            // Nothing in a response is unrepresentable; this is a defect, but it must not kill the loop.
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,"
                    + "\"message\":\"Could not serialise the response.\"}}";
        }
    }
}
