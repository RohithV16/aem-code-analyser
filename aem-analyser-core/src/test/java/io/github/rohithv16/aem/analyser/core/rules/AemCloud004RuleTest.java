package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud004Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AEM-CLOUD-004 must see deprecated types however the compilation unit names them: a fully
 * qualified use is as much a use as an import, and the import case must keep reporting once.
 */
class AemCloud004RuleTest {

    @TempDir
    Path root;

    @Test
    void fullyQualifiedUseWithoutImportIsFlagged() throws IOException {
        Path file = source("FullyQualified.java", """
                package com.example;

                public class FullyQualified {
                    public String mode() {
                        return com.day.cq.wcm.api.WCMMode.EDIT.name();
                    }
                }
                """);

        List<Violation> violations = new AemCloud004Rule().evaluate(context(file));

        assertEquals(1, violations.size(), "a fully-qualified use must produce exactly one finding");
        Violation violation = violations.get(0);
        assertEquals(RuleId.AEM_CLOUD_004.id(), violation.ruleId());
        assertEquals(5, violation.region().startLine(), "the finding must point at the use site");
        assertTrue(violation.message().contains("com.day.cq.wcm.api.WCMMode"), violation.message());
        assertTrue(violation.message().startsWith("Fully-qualified use"), violation.message());
    }

    @Test
    void importedUseIsStillReportedExactlyOnce() throws IOException {
        Path file = source("Imported.java", """
                package com.example;

                import com.day.cq.wcm.api.WCMMode;

                public class Imported {
                    public String mode() {
                        return WCMMode.EDIT.name();
                    }
                }
                """);

        List<Violation> violations = new AemCloud004Rule().evaluate(context(file));

        assertEquals(1, violations.size(), "an imported type is reported at the import, not per use");
        assertEquals(3, violations.get(0).region().startLine());
        assertTrue(violations.get(0).message().startsWith("Import "), violations.get(0).message());
    }

    @Test
    void cleanSourceProducesNoFinding() throws IOException {
        Path file = source("Clean.java", """
                package com.example;

                import java.util.List;

                public class Clean {
                    public List<String> names() {
                        return List.of("a", "b");
                    }
                }
                """);

        assertTrue(new AemCloud004Rule().evaluate(context(file)).isEmpty());
    }

    @Test
    void commentMentionIsNotAUse() throws IOException {
        Path file = source("Commented.java", """
                package com.example;

                /** Replaces com.day.cq.wcm.api.WCMMode, which is gone on AEMaaCS. */
                public class Commented {
                    // TODO: drop com.day.cq.wcm.api.WCMMode once the migration lands
                }
                """);

        assertTrue(new AemCloud004Rule().evaluate(context(file)).isEmpty(),
                "a Javadoc or line comment is not a use of the type");
    }

    @Test
    void longerNameThatOnlyStartsWithTheDeprecatedTypeIsNotFlagged() throws IOException {
        Path file = source("Lookalike.java", """
                package com.example;

                public class Lookalike {
                    Object mode = com.day.cq.wcm.api.WCMModeCompat.INSTANCE;
                }
                """);

        assertTrue(new AemCloud004Rule().evaluate(context(file)).isEmpty(),
                "a different type sharing the FQN prefix must not be reported");
    }

    private Path source(String name, String content) throws IOException {
        Path dir = root.resolve("bundle/src/main/java/com/example");
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private RuleContext context(Path file) {
        JavaClassNode node = new JavaClassNode("com.example." + file.getFileName().toString()
                .replace(".java", ""), JavaClassNode.Kind.CLASS,
                Collections.emptySet(), null, Collections.emptySet(), file,
                new SourceLocation(file, 1, 1));
        ReferenceGraph graph = new ReferenceGraph();
        graph.addVertex(node);
        ReferenceGraphBuilder.GraphBuildResult result = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(), Collections.emptyMap(),
                Collections.emptyMap(), Map.of(node.getFqn(), node), Collections.emptyMap(),
                List.of(), List.of(), List.of(), List.of(), null);
        return new RuleContext(graph, result, Collections.emptySet(), null, Collections.emptySet(), root);
    }
}
