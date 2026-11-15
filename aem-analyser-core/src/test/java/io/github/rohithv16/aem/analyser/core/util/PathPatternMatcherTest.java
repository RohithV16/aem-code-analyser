package io.github.rohithv16.aem.analyser.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathPatternMatcherTest {

    @TempDir
    Path projectRoot;

    @Test
    void matchesReactorRelativeGlob() {
        Path file = projectRoot.resolve(
                "ui.config/src/main/content/jcr_root/apps/sample/osgiconfig/config.publish/com.example.Pid.cfg.json");
        PathPatternMatcher matcher = new PathPatternMatcher(projectRoot,
                Set.of("ui.config/**/config.publish/**"));

        assertTrue(matcher.matches(file));
    }

    @Test
    void doesNotMatchOtherRunmodeFolder() {
        Path file = projectRoot.resolve(
                "ui.config/src/main/content/jcr_root/apps/sample/osgiconfig/config.author/com.example.Pid.cfg.json");
        PathPatternMatcher matcher = new PathPatternMatcher(projectRoot,
                Set.of("ui.config/**/config.publish/**"));

        assertFalse(matcher.matches(file));
    }

    @Test
    void bareFileNamePatternMatchesAnyDirectory() {
        Path file = projectRoot.resolve("some/deep/path/com.example.Pid.cfg.json");
        PathPatternMatcher matcher = new PathPatternMatcher(projectRoot, Set.of("*.cfg.json"));

        assertTrue(matcher.matches(file));
    }

    @Test
    void absolutePatternMatches() {
        Path file = projectRoot.resolve("ui.config/com.example.Pid.cfg.json").toAbsolutePath();
        PathPatternMatcher matcher = new PathPatternMatcher(projectRoot,
                Set.of(file.getParent().toAbsolutePath() + "/**"));

        assertTrue(matcher.matches(file));
    }

    @Test
    void windowsSeparatorsInPatternAreNormalized() {
        Path file = projectRoot.resolve("ui.config/com.example.Pid.cfg.json");
        PathPatternMatcher matcher = new PathPatternMatcher(projectRoot,
                Set.of("ui.config\\**"));

        assertTrue(matcher.matches(file));
    }

    @Test
    void trailingSlashBehavesAsRecursiveGlob() {
        Path file = projectRoot.resolve("ui.config/config.publish/com.example.Pid.cfg.json");
        PathPatternMatcher matcher = new PathPatternMatcher(projectRoot, Set.of("ui.config/"));

        assertTrue(matcher.matches(file));
    }

    @Test
    void emptyPatternSetMatchesNothing() {
        PathPatternMatcher matcher = new PathPatternMatcher(projectRoot, Set.of());

        assertTrue(matcher.isEmpty());
        assertFalse(matcher.matches(projectRoot.resolve("ui.config/com.example.Pid.cfg.json")));
    }
}
