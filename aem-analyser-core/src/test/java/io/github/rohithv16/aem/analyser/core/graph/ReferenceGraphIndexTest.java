package io.github.rohithv16.aem.analyser.core.graph;

import io.github.rohithv16.aem.analyser.core.model.graph.ConfigFormat;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceGraphIndexTest {

    private final Path root = Path.of("/mock/project");

    @Test
    void indexesConfigUnderEveryIndividualRunmode() {
        OSGiConfigNode config = config("com.example.Service", Set.of("author", "dev"));
        ReferenceGraphIndex index = new ReferenceGraphIndex();

        index.registerConfig(config);

        assertEquals(List.of(config), index.getConfigsForRunmode("author"));
        assertEquals(List.of(config), index.getConfigsForRunmode("dev"));
        assertTrue(index.getConfigsForRunmode("publish").isEmpty());
    }

    @Test
    void runmodeLookupIsCaseInsensitive() {
        OSGiConfigNode config = config("com.example.Service", Set.of("Author"));
        ReferenceGraphIndex index = new ReferenceGraphIndex();

        index.registerConfig(config);

        assertEquals(List.of(config), index.getConfigsForRunmode("author"));
        assertEquals(List.of(config), index.getConfigsForRunmode("AUTHOR"));
    }

    private OSGiConfigNode config(String pid, Set<String> runmodes) {
        Path file = root.resolve("ui.config/src/jcr_root/apps/sample/osgiconfig/config.author/com.example.Service.cfg.json");
        return new OSGiConfigNode(
                pid, file, SourceLocation.wholeFile(file), pid, null, false, runmodes, ConfigFormat.CFG_JSON);
    }
}
