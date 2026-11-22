package io.github.rohithv16.aem.analyser.core.parser.cache;

import io.github.rohithv16.aem.analyser.core.api.AnalysisContext;
import io.github.rohithv16.aem.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.export.SarifReportExporter;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #61: an unchanged file is parsed once per machine, rules still see the whole graph, and a cold
 * rerun is byte-identical to a warm one.
 */
class ParseCacheTest {

    @TempDir
    Path projectRoot;

    private Path write(String relativePath, String content) throws Exception {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private void writeProject() throws Exception {
        write("pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>cache-fixture</artifactId>
                  <version>1.0.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                    <module>ui.apps</module>
                  </modules>
                </project>
                """);
        write("core/pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>cache-fixture</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                  </parent>
                  <artifactId>cache-fixture-core</artifactId>
                  <packaging>jar</packaging>
                </project>
                """);
        write("ui.apps/pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>cache-fixture</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                  </parent>
                  <artifactId>cache-fixture-ui.apps</artifactId>
                  <packaging>content-package</packaging>
                </project>
                """);

        // An unused OSGi service: its finding needs the whole graph, so it must survive a cache hit.
        write("core/src/main/java/com/example/services/DeadService.java", """
                package com.example.services;
                public interface DeadService {
                    void execute();
                }
                """);
        write("core/src/main/java/com/example/services/impl/DeadServiceImpl.java", """
                package com.example.services.impl;
                import com.example.services.DeadService;
                import org.osgi.service.component.annotations.Component;
                @Component(service = DeadService.class, immediate = true)
                public class DeadServiceImpl implements DeadService {
                    @Override
                    public void execute() {
                    }
                }
                """);
        write("core/src/main/java/com/example/models/HeroModel.java", """
                package com.example.models;
                public class HeroModel {
                    public String getTitle() {
                        return "hero";
                    }
                }
                """);
        write("ui.apps/src/main/content/jcr_root/apps/example/components/hero/hero.html", """
                <div data-sly-use.model="com.example.models.HeroModel">${model.title}</div>
                """);
        write("ui.apps/src/main/content/jcr_root/apps/example/osgiconfig/config/com.example.services.impl.DeadServiceImpl.cfg.json",
                """
                        {
                          "enabled": true
                        }
                        """);
    }

    private String sarif(AnalysisReport report) throws Exception {
        StringWriter writer = new StringWriter();
        new SarifReportExporter().export(report, writer);
        return writer.toString();
    }

    private AnalysisReport analyze(boolean cache) throws Exception {
        return new DefaultAemCodeAnalyser().analyze(
                AnalysisContext.builder(projectRoot).enableCache(cache).enableSonar(false).build());
    }

    @Test
    void secondRunParsesNothingAndReproducesTheReport() throws Exception {
        writeProject();
        Path cacheDir = projectRoot.resolve("target/aem-analyser/cache/parse-v1");

        AnalysisReport first = analyze(true);
        String firstSarif = sarif(first);
        assertNotNull(first.cacheSummary());
        assertTrue(first.cacheSummary().endsWith("misses") || first.cacheSummary().endsWith("miss"),
                () -> "cold run must miss: " + first.cacheSummary());
        assertTrue(Files.isDirectory(cacheDir), "cold run writes the cache");

        AnalysisReport second = analyze(true);
        assertEquals(0, cacheDirMisses(second), () -> "warm run must parse nothing: " + second.cacheSummary());
        assertEquals(first.violations().size(), second.violations().size());
        assertEquals(firstSarif, sarif(second), "a warm run must be byte-identical to the cold one");

        // Rules still evaluate the whole graph on a hit: the cross-file dead-service finding is there.
        assertTrue(second.violations().stream()
                        .anyMatch(v -> RuleId.AEM_DEAD_003A.id().equals(v.ruleId())),
                () -> "cache hit dropped a cross-file finding: " + second.violations());

        // Deleting the cache reproduces the very first run, byte for byte.
        deleteRecursively(cacheDir);
        AnalysisReport coldAgain = analyze(true);
        assertEquals(firstSarif, sarif(coldAgain));
    }

    @Test
    void editingOneFileMissesExactlyOnce() throws Exception {
        writeProject();
        analyze(true);

        Path model = projectRoot.resolve("core/src/main/java/com/example/models/HeroModel.java");
        Files.writeString(model, Files.readString(model) + System.lineSeparator() + "// edited");

        AnalysisReport afterEdit = analyze(true);
        assertTrue(afterEdit.cacheSummary().endsWith("1 miss"),
                () -> "exactly one file changed: " + afterEdit.cacheSummary());

        AnalysisReport untouched = analyze(true);
        assertEquals(0, cacheDirMisses(untouched));
    }

    @Test
    void noCacheWritesNothing() throws Exception {
        writeProject();

        AnalysisReport report = analyze(false);

        assertNull(report.cacheSummary());
        assertFalse(Files.exists(projectRoot.resolve("target/aem-analyser/cache")),
                "--no-cache must not leave a cache behind");
        assertTrue(report.violations().stream()
                .anyMatch(v -> RuleId.AEM_DEAD_003A.id().equals(v.ruleId())));
    }

    @Test
    void deletingAnEntryIsAMissNotAFailureAndPruneDropsUnusedEntries() throws Exception {
        writeProject();
        Path cacheDir = projectRoot.resolve("target/aem-analyser/cache/parse-v1");
        analyze(true);
        int populated = jsonEntries(cacheDir);
        assertTrue(populated > 0);

        // Remove one source file: its entry is pruned on the next full run.
        Path model = projectRoot.resolve("core/src/main/java/com/example/models/HeroModel.java");
        Files.delete(model);
        AnalysisReport afterDelete = analyze(true);
        assertNotNull(afterDelete);
        assertTrue(jsonEntries(cacheDir) < populated,
                "entries for removed files must be pruned");

        // A corrupted entry degrades to a miss; the analysis still succeeds and is correct.
        List<Path> entries;
        try (var stream = Files.list(cacheDir)) {
            entries = stream.toList();
        }
        Files.writeString(entries.get(0), "{not json");
        AnalysisReport afterCorruption = analyze(true);
        assertTrue(afterCorruption.cacheSummary().endsWith("1 miss"),
                () -> "a corrupt entry must be re-parsed: " + afterCorruption.cacheSummary());
    }

    private static int cacheDirMisses(AnalysisReport report) {
        String summary = report.cacheSummary();
        assertNotNull(summary);
        int slash = summary.indexOf('/');
        String tail = summary.substring(slash + 1).trim();
        return Integer.parseInt(tail.substring(0, tail.indexOf(' ')));
    }

    private static int jsonEntries(Path cacheDir) throws IOException {
        try (var stream = Files.list(cacheDir)) {
            return (int) stream.filter(p -> p.getFileName().toString().endsWith(".json")).count();
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }
}
