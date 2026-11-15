package io.github.rohithv16.aem.analyser.core.git;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves changed files and modified line ranges using local git diff.
 * Enables PR and pre-commit scans (--changed-only, --since <ref>) without external dependencies.
 */
public final class GitDiffResolver {

    private static final Pattern HUNK_PATTERN = Pattern.compile("@@\\s+-[0-9,]+\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@");

    public record GitDiffResult(
            boolean isGitRepo,
            Set<String> changedFiles,
            Map<String, Set<Integer>> changedLines) {

        public boolean matches(String filePath, int line) {
            if (!isGitRepo) {
                return true; // Not in git repo: don't filter out anything
            }
            if (filePath == null || filePath.isBlank()) {
                return true;
            }
            String normalizedPath = filePath.replace('\\', '/').replaceAll("^/+", "");
            boolean fileChanged = changedFiles.stream()
                    .anyMatch(cf -> normalizedPath.endsWith(cf) || cf.endsWith(normalizedPath));
            if (!fileChanged) {
                return false;
            }
            Set<Integer> lines = null;
            for (Map.Entry<String, Set<Integer>> entry : changedLines.entrySet()) {
                String cf = entry.getKey();
                if (normalizedPath.endsWith(cf) || cf.endsWith(normalizedPath)) {
                    lines = entry.getValue();
                    break;
                }
            }
            if (lines == null || lines.isEmpty() || line <= 0) {
                return true; // File changed, line numbers unknown or whole-file: match
            }
            return lines.contains(line);
        }
    }

    public GitDiffResult resolveChanged(Path projectRoot, String sinceRef) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return new GitDiffResult(false, Collections.emptySet(), Collections.emptyMap());
        }

        Path gitDir = findGitDir(projectRoot);
        if (gitDir == null) {
            return new GitDiffResult(false, Collections.emptySet(), Collections.emptyMap());
        }

        Set<String> changedFiles = new HashSet<>();
        Map<String, Set<Integer>> changedLines = new HashMap<>();

        try {
            // 1. Check git status for unstaged/untracked files
            runGitCommand(projectRoot, new String[]{"git", "status", "--porcelain"}, line -> {
                if (line.length() >= 4) {
                    String status = line.substring(0, 2);
                    String file = line.substring(3).trim();
                    if (!status.startsWith("D")) {
                        changedFiles.add(normalize(file));
                    }
                }
            });

            // 2. Run git diff -U0 to capture modified files and line ranges
            String[] diffCmd;
            if (sinceRef != null && !sinceRef.isBlank()) {
                diffCmd = new String[]{"git", "diff", "-U0", sinceRef.trim()};
            } else {
                diffCmd = new String[]{"git", "diff", "-U0", "HEAD"};
            }

            final String[] currentFile = new String[1];
            runGitCommand(projectRoot, diffCmd, line -> {
                if (line.startsWith("+++ b/")) {
                    String rel = normalize(line.substring(6));
                    currentFile[0] = rel;
                    changedFiles.add(rel);
                } else if (line.startsWith("@@") && currentFile[0] != null) {
                    Matcher matcher = HUNK_PATTERN.matcher(line);
                    if (matcher.find()) {
                        int startLine = Integer.parseInt(matcher.group(1));
                        int count = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
                        Set<Integer> lines = changedLines.computeIfAbsent(currentFile[0], k -> new HashSet<>());
                        for (int i = 0; i < count; i++) {
                            lines.add(startLine + i);
                        }
                    }
                }
            });

            return new GitDiffResult(true, Collections.unmodifiableSet(changedFiles), Collections.unmodifiableMap(changedLines));
        } catch (Exception e) {
            // Fallback: if git fails, do not drop violations
            return new GitDiffResult(false, Collections.emptySet(), Collections.emptyMap());
        }
    }

    private Path findGitDir(Path dir) {
        Path cur = dir.toAbsolutePath().normalize();
        while (cur != null) {
            if (Files.isDirectory(cur.resolve(".git")) || Files.isRegularFile(cur.resolve(".git"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        return null;
    }

    private String normalize(String path) {
        return path.replace('\\', '/').replaceAll("^/+", "").trim();
    }

    private void runGitCommand(Path dir, String[] command, java.util.function.Consumer<String> lineConsumer) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = reader.readLine()) != null) {
                lineConsumer.accept(l);
            }
        }
        p.waitFor();
    }
}
