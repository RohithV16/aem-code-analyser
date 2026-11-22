package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemSec002Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AEM-SEC-002: a {@code final} local is only a constant when its initializer is a constant
 * expression, so request-derived data laundered through one is still an open redirect / SSRF.
 */
class AemSec002RuleTest {

    @TempDir
    Path root;

    private List<Violation> evaluate(String source) throws Exception {
        Path file = root.resolve("Servlet.java");
        Files.writeString(file, source);
        JavaClassNode node = new JavaClassNode("com.example.Servlet", JavaClassNode.Kind.CLASS,
                Collections.emptySet(), null, Collections.emptySet(), file, new SourceLocation(file, 1, 1));
        ReferenceGraph graph = new ReferenceGraph();
        graph.addVertex(node);
        ReferenceGraphBuilder.GraphBuildResult result = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(),
                java.util.Map.of(node.getFqn(), node),
                Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(), null);
        return new AemSec002Rule().evaluate(
                new RuleContext(graph, result, Collections.emptySet(), null, Collections.emptySet(), root));
    }

    /** The exact snippet from issue #4: a {@code final} local hides the request parameter. */
    @Test
    void finalLocalHoldingRequestParameterIsFlagged() throws Exception {
        List<Violation> violations = evaluate("""
                package com.example;
                class Servlet {
                    void run(Object request, Object response) throws Exception {
                        final String target = request.getParameter("to");
                        response.sendRedirect(target);
                    }
                }
                """);

        assertEquals(1, violations.size(), () -> "expected one open redirect, got " + violations);
        assertEquals(RuleId.AEM_SEC_002.id(), violations.get(0).ruleId());
        assertEquals("OPEN_REDIRECT", violations.get(0).subtype());
    }

    /** Issue #4 also reports {@code final String dest = base;} as silently clean. */
    @Test
    void finalAliasOfRequestDerivedValueIsFlagged() throws Exception {
        List<Violation> violations = evaluate("""
                package com.example;
                class Servlet {
                    void run(Object request, Object response) throws Exception {
                        String base = request.getParameter("base");
                        final String dest = base;
                        response.sendRedirect(dest);
                    }
                }
                """);

        assertEquals(1, violations.size(), () -> "expected one open redirect, got " + violations);
        assertEquals("OPEN_REDIRECT", violations.get(0).subtype());
    }

    @Test
    void nonFinalLocalIsStillFlagged() throws Exception {
        List<Violation> violations = evaluate("""
                package com.example;
                class Servlet {
                    void run(Object request, Object response) throws Exception {
                        String target = request.getParameter("to");
                        response.sendRedirect(target);
                    }
                }
                """);

        assertEquals(1, violations.size());
    }

    @Test
    void staticFinalLiteralIsNotFlagged() throws Exception {
        List<Violation> violations = evaluate("""
                package com.example;
                class Servlet {
                    private static final String SAFE_TARGET = "/content/acme/en.html";
                    void run(Object response) throws Exception {
                        response.sendRedirect(SAFE_TARGET);
                    }
                }
                """);

        assertEquals(0, violations.size(), () -> "a literal constant is safe, got " + violations);
    }

    @Test
    void transitiveConstantConcatenationIsNotFlagged() throws Exception {
        List<Violation> violations = evaluate("""
                package com.example;
                class Servlet {
                    private static final String BASE = "https://www.example.com";
                    private static final String TARGET = BASE + "/content/acme/en.html";
                    private static final String DEEP = TARGET + "?ref=a+b";
                    void run(Object response) throws Exception {
                        response.sendRedirect(DEEP);
                    }
                }
                """);

        assertEquals(0, violations.size(), () -> "constant concatenation is safe, got " + violations);
    }

    @Test
    void qualifiedConstantIsNotFlagged() throws Exception {
        List<Violation> violations = evaluate("""
                package com.example;
                class Servlet {
                    void run(Object response) throws Exception {
                        response.sendRedirect(AcmeConstants.PID);
                    }
                }
                """);

        assertEquals(0, violations.size());
    }

    /** A constant declaration holding a call result is not a constant expression. */
    @Test
    void finalStringBuiltFromMethodCallIsFlagged() throws Exception {
        List<Violation> violations = evaluate("""
                package com.example;
                class Servlet {
                    void run(Object config, Object response) throws Exception {
                        final String target = config.resolve("to");
                        response.sendRedirect(target);
                    }
                }
                """);

        assertEquals(1, violations.size());
    }

    @Test
    void ssrfThroughFinalLocalIsFlagged() throws Exception {
        List<Violation> violations = evaluate("""
                package com.example;
                class Client {
                    void run(Object request, Object response) throws Exception {
                        final String endpoint = request.getParameter("endpoint");
                        URL url = new URL(endpoint);
                    }
                }
                """);

        assertEquals(1, violations.size(), () -> "expected one SSRF, got " + violations);
        assertEquals("SSRF", violations.get(0).subtype());
    }

    @Test
    void ssrfThroughAliasedParameterIsFlagged() throws Exception {
        List<Violation> violations = evaluate("""
                package com.example;
                class Client {
                    void run(Object request) throws Exception {
                        String host = request.getParameter("host");
                        final String endpoint = host;
                        URL url = new URL(endpoint);
                    }
                }
                """);

        assertEquals(1, violations.size());
        assertEquals("SSRF", violations.get(0).subtype());
    }

    @Test
    void ssrfToConstantEndpointIsNotFlagged() throws Exception {
        List<Violation> violations = evaluate("""
                package com.example;
                class Client {
                    private static final String ENDPOINT = "https://api.internal.example.com/";
                    void run() throws Exception {
                        URL url = new URL(ENDPOINT);
                    }
                }
                """);

        assertEquals(0, violations.size(), () -> "constant endpoint is safe, got " + violations);
    }

    @Test
    void ssrfToConfiguredHostIsNotFlagged() throws Exception {
        List<Violation> violations = evaluate("""
                package com.example;
                class Client {
                    private final String endpoint;
                    Client(String endpoint) { this.endpoint = endpoint; }
                    void run() throws Exception {
                        URL url = new URL(endpoint);
                    }
                }
                """);

        assertEquals(0, violations.size(),
                () -> "a constructor-injected endpoint is not request-controlled here, got " + violations);
    }

    @Test
    void ssrfWithRequestInputInConcatenationIsFlagged() throws Exception {
        List<Violation> violations = evaluate("""
                package com.example;
                class Client {
                    private static final String BASE = "https://api.example.com/";
                    void run(Object request) throws Exception {
                        URL url = new URL(BASE + request.getParameter("path"));
                    }
                }
                """);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).subtype().equals("SSRF"));
    }
}
