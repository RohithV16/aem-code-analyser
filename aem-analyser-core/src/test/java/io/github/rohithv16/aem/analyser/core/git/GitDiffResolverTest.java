package io.github.rohithv16.aem.analyser.core.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitDiffResolverTest {

    @Test
    void testNonGitDirectoryFallsBackGracefully(@TempDir Path tempDir) {
        GitDiffResolver resolver = new GitDiffResolver();
        GitDiffResolver.GitDiffResult result = resolver.resolveChanged(tempDir, null);

        assertFalse(result.isGitRepo());
        assertTrue(result.matches("any/file.java", 42), "Non-git repo should match all files/lines");
    }

    @Test
    void testGitDiffResultMatching() {
        GitDiffResolver.GitDiffResult result = new GitDiffResolver.GitDiffResult(
                true,
                Set.of("core/src/main/java/MyService.java"),
                Map.of("core/src/main/java/MyService.java", Set.of(15, 16, 17))
        );

        assertTrue(result.matches("core/src/main/java/MyService.java", 15));
        assertTrue(result.matches("core/src/main/java/MyService.java", 16));
        assertFalse(result.matches("core/src/main/java/MyService.java", 20));
        assertFalse(result.matches("core/src/main/java/OtherService.java", 15));
    }

    @Test
    void testCurrentGitRepositoryResolution() {
        Path repoRoot = Path.of(".").toAbsolutePath().normalize();
        GitDiffResolver resolver = new GitDiffResolver();
        GitDiffResolver.GitDiffResult result = resolver.resolveChanged(repoRoot, null);

        assertTrue(result.isGitRepo(), "Current repository should be identified as git repository");
    }
}
