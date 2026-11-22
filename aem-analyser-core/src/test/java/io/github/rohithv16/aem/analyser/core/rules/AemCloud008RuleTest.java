package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud008Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AEM-CLOUD-008 against the shapes from the issue: a chained write to {@code /apps}, a chained
 * {@code setProperty} on a {@code /libs} node, the two-statement variable form, and the negative
 * controls that pin the severity — a {@code /var} write (the portfolio's cursor state), a read of
 * {@code /apps}, and a look-alike path such as {@code /apps_old}.
 */
class AemCloud008RuleTest {

    @TempDir
    Path root;

    @Test
    void chainedWritesIntoAppsAndLibsAreReported() throws Exception {
        java("core/src/main/java/com/acme/Writer.java", """
                package com.acme;

                import org.apache.sling.api.resource.ModifiableValueMap;

                public class Writer {
                    public void write(ResourceResolver resolver, Session session) {
                        resolver.getResource("/apps/acme/config")
                                .adaptTo(ModifiableValueMap.class)
                                .put("enabled", Boolean.TRUE);
                        session.getNode("/libs/acme/settings").setProperty("mode", "on");
                    }
                }
                """);

        List<Violation> violations = evaluate();

        assertEquals(2, violations.size(), () -> "One /apps and one /libs write: " + violations);
        assertTrue(violations.stream().allMatch(v -> v.subtype().equals("IMMUTABLE_PATH_WRITE")));
        assertTrue(violations.stream().allMatch(v -> v.severity() == Severity.ERROR));
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("'/apps/acme/config'")
                && v.message().contains("put")), () -> violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("'/libs/acme/settings'")
                && v.message().contains("setProperty")), () -> violations.toString());
        assertEquals(7, violations.get(0).region().startLine(), "the finding points at the path call");
    }

    @Test
    void writeThroughAVariableIsStillAWrite() throws Exception {
        java("core/src/main/java/com/acme/Labels.java", """
                package com.acme;

                public class Labels {
                    void relabel(Session session) throws Exception {
                        Node node = session.getNode("/apps/acme/i18n/labels");
                        node.setProperty("title", "New");
                    }
                }
                """);

        List<Violation> violations = evaluate();

        assertEquals(1, violations.size(), () -> violations.toString());
        assertTrue(violations.get(0).message().contains("/apps/acme/i18n/labels"));
    }

    @Test
    void rootRelativeAddNodeOfAnImmutableAreaIsReported() throws Exception {
        java("core/src/main/java/com/acme/Bootstrap.java", """
                package com.acme;

                public class Bootstrap {
                    void create(Session session) throws Exception {
                        session.getRootNode().addNode("apps").addNode("acme");
                    }
                }
                """);

        List<Violation> violations = evaluate();

        assertEquals(1, violations.size(), () -> violations.toString());
        assertTrue(violations.get(0).message().contains("'/apps'"));
    }

    @Test
    void mutablePathWriteIsSilent() throws Exception {
        // The portfolio's CursorStateServiceImpl: the state path is a class constant under /var.
        java("core/src/main/java/com/acme/CursorStateServiceImpl.java", """
                package com.acme;

                import org.apache.sling.api.resource.ModifiableValueMap;

                public class CursorStateServiceImpl {
                    private static final String CURSOR_PATH = "/var/rohithaem-portfolio/cursor";

                    void store(ResourceResolver resolver, long cursor) {
                        ModifiableValueMap state =
                                resolver.getResource(CURSOR_PATH).adaptTo(ModifiableValueMap.class);
                        state.put("cursor", cursor);
                        resolver.commit();
                    }
                }
                """);

        assertTrue(evaluate().isEmpty(),
                "A runtime write under /var is exactly what the platform expects; it must stay silent");
    }

    @Test
    void readingAppsOrLibsIsNotAWrite() throws Exception {
        java("core/src/main/java/com/acme/Reader.java", """
                package com.acme;

                public class Reader {
                    String read(ResourceResolver resolver, Session session) throws Exception {
                        String fromResource = resolver.getResource("/apps/acme/config").getName();
                        String fromNode = session.getNode("/libs/acme/settings").getPath();
                        return fromResource + fromNode;
                    }
                }
                """);

        assertTrue(evaluate().isEmpty(), "A read of /apps or /libs is how every component loads config");
    }

    @Test
    void pathPrefixThatIsNotAnImmutableRootIsSilent() throws Exception {
        java("core/src/main/java/com/acme/Lookalike.java", """
                package com.acme;

                public class Lookalike {
                    void write(ResourceResolver resolver) {
                        resolver.getResource("/apps_old/acme/config")
                                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class)
                                .put("x", 1);
                    }
                }
                """);

        assertTrue(evaluate().isEmpty(), "/apps_old is not /apps; the match is on a path segment");
    }

    private void java(String relative, String source) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private List<Violation> evaluate() {
        ReferenceGraph graph = new ReferenceGraph();
        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), List.of(), List.of(), null);
        RuleContext context = new RuleContext(graph, buildResult, Collections.emptySet(), null,
                Collections.emptySet(), root);
        return new AemCloud008Rule().evaluate(context);
    }
}
