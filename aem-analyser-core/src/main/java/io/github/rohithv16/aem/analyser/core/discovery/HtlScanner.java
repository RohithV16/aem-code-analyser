package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.htl.HtlUsage;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discovers and extracts data-sly-use usages from HTL (.html) templates.
 */
public final class HtlScanner {

    private static final Pattern SLY_USE_PATTERN = Pattern.compile(
            "data-sly-use(?:\\.([a-zA-Z0-9_$-]+))?\\s*=\\s*([\"'])(.*?)\\2"
    );

    private static final Pattern EXPR_TARGET_PATTERN = Pattern.compile(
            "^\\s*\\$\\{\\s*[\"']([^\"']+)[\"']\\s*\\}\\s*$"
    );

    public List<HtlUsage> scanDirectory(Path directory) {
        List<HtlUsage> usages = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return usages;
        }

        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".html"))
                    .filter(this::isNotIgnored)
                    .forEach(htmlFile -> scanFile(htmlFile, usages));
        } catch (IOException ignored) {
        }

        return usages;
    }

    public void scanFile(Path htmlFile, List<HtlUsage> collector) {
        try (BufferedReader reader = Files.newBufferedReader(htmlFile)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                Matcher matcher = SLY_USE_PATTERN.matcher(line);
                while (matcher.find()) {
                    String identifier = matcher.group(1);
                    String rawTarget = matcher.group(3);
                    String target = rawTarget;

                    Matcher exprMatcher = EXPR_TARGET_PATTERN.matcher(rawTarget);
                    if (exprMatcher.matches()) {
                        target = exprMatcher.group(1);
                    }

                    int col = matcher.start() + 1;
                    collector.add(new HtlUsage(htmlFile, lineNum, col, identifier, target.strip()));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private boolean isNotIgnored(Path path) {
        String s = path.toString();
        return !s.contains("/target/") && !s.contains("/node_modules/") && !s.contains("/.git/");
    }
}
