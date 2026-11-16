package io.github.rohithv16.aem.analyser.core.git;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
            Map<String, Set<Integer>> changedLines,
            String failureReason) {

        public GitDiffResult(boolean isGitRepo, Set<String> changedFiles,
                             Map<String, Set<Integer>> changedLines) {
            this(isGitRepo, changedFiles, changedLines, null);
        }

        /**
         * True when git was reachable but a command failed (e.g. an unknown {@code --since} ref, or a
         * shallow CI clone that never fetched the base ref). Callers must NOT treat the empty
         * {@code changedFiles} of a failed resolution as "nothing changed".
         */
        public boolean failed() {
            return failureReason != null;
        }

        public boolean matches(String filePath, int line) {
            if (!isGitRepo) {
                return true; // Not in git repo: don't filter out anything
            }
            if (filePath == null || filePath.isBlank()) {
                return true;
            }
            String normalizedPath = filePath.replace('\\', '/').replaceAll("^/+", "");
            String matchedFile = changedFiles.stream()
                    .filter(cf -> normalizedPath.equals(cf) || normalizedPath.endsWith("/" + cf)
                            || cf.endsWith("/" + normalizedPath))
                    .findFirst()
                    .orElse(null);
            if (matchedFile == null) {
                return false;
            }
            Set<Integer> lines = changedLines.get(matchedFile);
            if (lines == null) {
                for (Map.Entry<String, Set<Integer>> entry : changedLines.entrySet()) {
                    if (entry.getKey().equals(matchedFile)) {
                        lines = entry.getValue();
                        break;
                    }
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
            readStatusPorcelainZ(projectRoot, changedFiles);

            // Committed changes since the merge base (PR semantics), when a ref is supplied.
            if (sinceRef != null && !sinceRef.isBlank()) {
                String error = runDiff(projectRoot, new String[]{
                        "git", "-c", "core.quotepath=false", "diff", "-U0",
                        sinceRef.trim() + "...HEAD"}, changedFiles, changedLines);
                if (error != null) {
                    // An unusable base ref must never look like "nothing changed": the caller would
                    // filter out every finding and report a clean, green build.
                    return new GitDiffResult(true, Collections.emptySet(), Collections.emptyMap(),
                            "git diff against '" + sinceRef.trim() + "' failed: " + error);
                }
            }
            // Uncommitted working-tree changes (staged and unstaged). A failure here is benign in a
            // repository without commits yet, so it is reported but not treated as fatal.
            runDiff(projectRoot, new String[]{
                    "git", "-c", "core.quotepath=false", "diff", "-U0", "HEAD"},
                    changedFiles, changedLines);

            return new GitDiffResult(true, Collections.unmodifiableSet(changedFiles),
                    Collections.unmodifiableMap(changedLines));
        } catch (Exception e) {
            // Fallback: if git fails, do not drop violations
            return new GitDiffResult(false, Collections.emptySet(), Collections.emptyMap());
        }
    }

    /**
     * Parses {@code git status --porcelain=v1 -z --untracked-files=all}. The NUL-separated form keeps
     * spaces/unicode intact, lists untracked files individually and gives rename old/new paths.
     */
    private void readStatusPorcelainZ(Path projectRoot, Set<String> changedFiles) throws Exception {
        byte[] bytes = runGitCommandBytes(projectRoot, new String[]{
                "git", "-c", "core.quotepath=false", "status",
                "--porcelain=v1", "-z", "--untracked-files=all"});
        String[] entries = new String(bytes, StandardCharsets.UTF_8).split("\0", -1);
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i];
            if (entry.length() < 4) {
                continue;
            }
            String status = entry.substring(0, 2);
            String path = entry.substring(3);
            boolean renamedOrCopied = status.startsWith("R") || status.startsWith("C");
            if (!status.startsWith("D")) {
                changedFiles.add(normalize(path));
            }
            if (renamedOrCopied && i + 1 < entries.length) {
                i++; // consume the old path that follows a rename/copy entry
            }
        }
    }

    /**
     * @return {@code null} when the diff ran successfully, otherwise the git error output
     */
    private String runDiff(Path projectRoot, String[] diffCmd,
                           Set<String> changedFiles, Map<String, Set<Integer>> changedLines) throws Exception {
        final String[] currentFile = new String[1];
        ProcessOutcome outcome = runGitCommand(projectRoot, diffCmd, line -> {
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
        return outcome.exitCode() == 0 ? null : outcome.errorText();
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
        return path.replace('\\', '/').replaceAll("^/+", "");
    }

    /** Exit status plus captured stderr of a git invocation. */
    private record ProcessOutcome(int exitCode, String errorText) {
    }

    private ProcessOutcome runGitCommand(Path dir, String[] command,
                                         java.util.function.Consumer<String> lineConsumer) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        // stderr is captured rather than discarded: a failing command (unknown ref, shallow clone)
        // must be reported, not silently turned into "no changed files".
        pb.redirectErrorStream(false);
        Process p = pb.start();
        StringBuilder errorText = new StringBuilder();
        Thread errorReader = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    if (errorText.length() < 4096) {
                        errorText.append(line).append(' ');
                    }
                }
            } catch (Exception ignored) {
                // best effort
            }
        }, "aem-analyser-git-stderr");
        errorReader.setDaemon(true);
        errorReader.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineConsumer.accept(line);
            }
        }
        int exitCode = p.waitFor();
        errorReader.join(2000);
        return new ProcessOutcome(exitCode, errorText.toString().strip());
    }

    private byte[] runGitCommandBytes(Path dir, String[] command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(buffer);
        }
        p.waitFor();
        return buffer.toByteArray();
    }
}
