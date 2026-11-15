package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.htl.HtlUnsafeContextFinding;
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
 * Discovers and extracts data-sly-use usages and security risks from HTL (.html) templates.
 */
public final class HtlScanner {

    public record HtlScanResult(List<HtlUsage> usages, List<HtlUnsafeContextFinding> unsafeContexts) {}

    private static final Pattern SLY_USE_PATTERN = Pattern.compile(
            "data-sly-use(?:\\.([a-zA-Z0-9_$-]+))?\\s*=\\s*([\"'])(.*?)\\2"
    );

    private static final Pattern EXPR_TARGET_PATTERN = Pattern.compile(
            "^\\s*\\$\\{\\s*[\"']([^\"']+)[\"']\\s*\\}\\s*$"
    );

    private static final Pattern UNSAFE_CONTEXT_PATTERN = Pattern.compile(
            "@\\s*context\\s*=\\s*['\"]unsafe['\"]",
            Pattern.CASE_INSENSITIVE
    );

    public HtlScanResult scan(Path directory) {
        List<HtlUsage> usages = new ArrayList<>();
        List<HtlUnsafeContextFinding> unsafeContexts = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return new HtlScanResult(usages, unsafeContexts);
        }

        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".html"))
                    .filter(this::isNotIgnored)
                    .forEach(htmlFile -> scanFile(htmlFile, usages, unsafeContexts));
        } catch (IOException ignored) {
        }

        return new HtlScanResult(usages, unsafeContexts);
    }

    public List<HtlUsage> scanDirectory(Path directory) {
        return scan(directory).usages();
    }

    public void scanFile(Path htmlFile, List<HtlUsage> usagesCollector, List<HtlUnsafeContextFinding> unsafeCollector) {
        try (BufferedReader reader = Files.newBufferedReader(htmlFile)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;

                // 1. Scan data-sly-use usages
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
                    usagesCollector.add(new HtlUsage(htmlFile, lineNum, col, identifier, target.strip()));
                }

                // 2. Scan unsafe context bypasses
                Matcher unsafeMatcher = UNSAFE_CONTEXT_PATTERN.matcher(line);
                while (unsafeMatcher.find()) {
                    int col = unsafeMatcher.start() + 1;
                    unsafeCollector.add(new HtlUnsafeContextFinding(htmlFile, lineNum, col, line.trim()));
                }
            }
        } catch (IOException ignored) {
        }
    }

    public void scanFile(Path htmlFile, List<HtlUsage> collector) {
        scanFile(htmlFile, collector, new ArrayList<>());
    }

    private boolean isNotIgnored(Path path) {
        String s = path.toString();
        return !s.contains("/target/") && !s.contains("/node_modules/") && !s.contains("/.git/");
    }
}
