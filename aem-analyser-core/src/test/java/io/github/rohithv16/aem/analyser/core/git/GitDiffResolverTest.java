package io.github.rohithv16.aem.analyser.core.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void resolvesRenamesUntrackedDirectoriesAndPathsWithSpaces(@TempDir Path repo) throws Exception {
        git(repo, "init");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("Old.java"), "class Old { }\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "init");

        git(repo, "mv", "Old.java", "Renamed.java");
        Path untracked = repo.resolve("new dir/File One.java");
        Files.createDirectories(untracked.getParent());
        Files.writeString(untracked, "class FileOne { }\n");

        GitDiffResolver.GitDiffResult result = new GitDiffResolver().resolveChanged(repo, null);

        assertTrue(result.isGitRepo());
        assertTrue(result.changedFiles().contains("Renamed.java"), result.changedFiles()::toString);
        assertFalse(result.changedFiles().contains("Old.java"), result.changedFiles()::toString);
        assertTrue(result.changedFiles().contains("new dir/File One.java"), result.changedFiles()::toString);
        assertTrue(result.matches("new dir/File One.java", 1));
    }

    private void git(Path repo, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(), "git " + String.join(" ", arguments) + " failed");
    }
}
