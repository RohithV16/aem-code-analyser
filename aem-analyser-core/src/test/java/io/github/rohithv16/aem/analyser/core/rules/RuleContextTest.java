package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleContextTest {

    private static RuleContext context(Path root) {
        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), java.util.List.of(), null);
        return new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
    }

    /**
     * Regression (Windows CI): a rooted-but-driveless root such as {@code /mock/project} is not the
     * same path type as the absolutised violation path, so {@code Path.relativize} threw
     * "'other' is different type of Path" and every rule crashed. The root must always be absolute.
     */
    @Test
    void projectRootIsAlwaysAbsolute() {
        RuleContext relativised = context(Path.of("relative/root"));
        assertTrue(relativised.projectRoot().isAbsolute(), "relative root must be absolutised");

        RuleContext rooted = context(Path.of("/mock/project"));
        assertTrue(rooted.projectRoot().isAbsolute(), "driveless root must be absolutised");
    }

    @Test
    void relativizeWorksForPathsUnderTheRoot() {
        RuleContext ctx = context(Path.of("/mock/project"));
        Path file = Path.of("/mock/project/ui.config/com.example.Foo.cfg.json").toAbsolutePath().normalize();
        assertEquals("ui.config/com.example.Foo.cfg.json",
                ctx.projectRoot().relativize(file).toString().replace('\\', '/'));
    }
}
