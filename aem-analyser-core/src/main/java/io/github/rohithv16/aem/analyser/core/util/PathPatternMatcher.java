package io.github.rohithv16.aem.analyser.core.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reactor-relative glob matcher used to exclude files (currently OSGi configuration files)
 * from analysis.
 *
 * <p>Supported syntax:
 * <ul>
 *   <li>{@code *} — any characters except {@code /}</li>
 *   <li>{@code **} — any characters including {@code /}</li>
 *   <li>{@code ?} — a single character except {@code /}</li>
 * </ul>
 *
 * <p>Patterns are matched against the reactor-relative path with {@code /} separators. A pattern
 * without a slash is additionally matched against the file name, so {@code *.cfg.json} works as
 * expected. Absolute paths and {@code ./} prefixes are normalized before matching.
 *
 * <p>Instances are immutable and safe to reuse for the duration of one analysis.
 */
public final class PathPatternMatcher {

    private final Path projectRoot;
    private final List<Pattern> pathPatterns = new ArrayList<>();
    private final List<Pattern> fileNamePatterns = new ArrayList<>();

    public PathPatternMatcher(Path projectRoot, Set<String> patterns) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        if (patterns == null) {
            return;
        }
        for (String raw : patterns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String pattern = normalize(raw);
            Pattern compiled = Pattern.compile(globToRegex(pattern));
            pathPatterns.add(compiled);
            if (!pattern.contains("/")) {
                fileNamePatterns.add(compiled);
            }
        }
    }

    public boolean isEmpty() {
        return pathPatterns.isEmpty();
    }

    /**
     * @return true when the file matches any configured ignore pattern
     */
    public boolean matches(Path file) {
        if (pathPatterns.isEmpty() || file == null) {
            return false;
        }
        Path absolute = file.toAbsolutePath().normalize();
        String relative = relativePath(absolute);
        String absoluteText = normalize(absolute.toString());
        String fileName = absolute.getFileName() == null ? "" : absolute.getFileName().toString();

        for (Pattern pattern : pathPatterns) {
            if (pattern.matcher(relative).matches() || pattern.matcher(absoluteText).matches()) {
                return true;
            }
        }
        for (Pattern pattern : fileNamePatterns) {
            if (pattern.matcher(fileName).matches()) {
                return true;
            }
        }
        return false;
    }

    private String relativePath(Path absolute) {
        if (projectRoot != null) {
            try {
                if (absolute.startsWith(projectRoot)) {
                    return projectRoot.relativize(absolute).toString().replace('\\', '/');
                }
            } catch (IllegalArgumentException ignored) {
                // Different roots (e.g. different drives on Windows): fall through.
            }
        }
        return normalize(absolute.toString());
    }

    private static String normalize(String value) {
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized + "**";
        }
        return normalized;
    }

    static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i++;
                    } else {
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' ->
                        regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return regex.append('$').toString();
    }
}
