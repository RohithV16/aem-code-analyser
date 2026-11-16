package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.htl.HtlUnsafeContextFinding;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlUsage;

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

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /** Same expression but across line breaks (multi-line HTL attributes). */
    private static final Pattern SLY_USE_PATTERN_MULTILINE = Pattern.compile(
            "data-sly-use(?:\\.([a-zA-Z0-9_$-]+))?\\s*=\\s*([\"'])(.*?)\\2",
            Pattern.DOTALL
    );

    private static final Pattern EXPR_TARGET_PATTERN = Pattern.compile(
            "^\\s*\\$\\{\\s*[\"']([^\"']+)[\"']\\s*\\}\\s*$"
    );

    private static final Pattern UNSAFE_CONTEXT_PATTERN = Pattern.compile(
            "@\\s*context\\s*=\\s*['\"]unsafe['\"]",
            Pattern.CASE_INSENSITIVE
    );

    public HtlScanResult scan(Path directory) {
        return scan(directory, new io.github.rohithv16.aem.analyser.core.util.DiagnosticSink());
    }

    public HtlScanResult scan(Path directory, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics) {
        return scan(directory, diagnostics, MAX_FILE_SIZE_BYTES);
    }

    public HtlScanResult scan(Path directory, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics,
                              long maxFileSizeBytes) {
        List<HtlUsage> usages = new ArrayList<>();
        List<HtlUnsafeContextFinding> unsafeContexts = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return new HtlScanResult(usages, unsafeContexts);
        }

        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".html"))
                    .filter(this::isNotIgnored)
                    .forEach(htmlFile -> scanFile(htmlFile, usages, unsafeContexts, diagnostics, maxFileSizeBytes));
        } catch (IOException e) {
            if (diagnostics != null) {
                diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.UNREADABLE_TREE,
                        directory, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink.reasonOf(e));
            }
        }

        return new HtlScanResult(usages, unsafeContexts);
    }

    public List<HtlUsage> scanDirectory(Path directory) {
        return scan(directory).usages();
    }

    public void scanFile(Path htmlFile, List<HtlUsage> usagesCollector, List<HtlUnsafeContextFinding> unsafeCollector) {
        scanFile(htmlFile, usagesCollector, unsafeCollector,
                new io.github.rohithv16.aem.analyser.core.util.DiagnosticSink());
    }

    public void scanFile(Path htmlFile, List<HtlUsage> usagesCollector,
                         List<HtlUnsafeContextFinding> unsafeCollector,
                         io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics) {
        scanFile(htmlFile, usagesCollector, unsafeCollector, diagnostics, MAX_FILE_SIZE_BYTES);
    }

    public void scanFile(Path htmlFile, List<HtlUsage> usagesCollector,
                         List<HtlUnsafeContextFinding> unsafeCollector,
                         io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics,
                         long maxFileSizeBytes) {
        try {
            if (Files.size(htmlFile) > maxFileSizeBytes) {
                if (diagnostics != null) {
                    diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.FILE_TOO_LARGE,
                            htmlFile, "exceeds " + (maxFileSizeBytes / (1024 * 1024)) + " MiB");
                }
                return;
            }
        } catch (IOException e) {
            if (diagnostics != null) {
                diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.UNREADABLE_TREE,
                        htmlFile, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink.reasonOf(e));
            }
            return;
        }
        try {
            String content = Files.readString(htmlFile);

            // 1. data-sly-use usages, including attributes that span multiple lines.
            Matcher matcher = SLY_USE_PATTERN_MULTILINE.matcher(content);
            while (matcher.find()) {
                String identifier = matcher.group(1);
                String rawTarget = matcher.group(3);
                String target = rawTarget;

                Matcher exprMatcher = EXPR_TARGET_PATTERN.matcher(rawTarget);
                if (exprMatcher.matches()) {
                    target = exprMatcher.group(1);
                }

                usagesCollector.add(new HtlUsage(htmlFile, lineNumber(content, matcher.start()),
                        column(content, matcher.start()), identifier, target.strip()));
            }

            // 2. Unsafe context bypasses, with a snippet from the containing line.
            Matcher unsafeMatcher = UNSAFE_CONTEXT_PATTERN.matcher(content);
            while (unsafeMatcher.find()) {
                int start = unsafeMatcher.start();
                int lineStart = content.lastIndexOf('\n', start) + 1;
                int lineEnd = content.indexOf('\n', start);
                if (lineEnd < 0) {
                    lineEnd = content.length();
                }
                unsafeCollector.add(new HtlUnsafeContextFinding(htmlFile,
                        lineNumber(content, start), column(content, start),
                        content.substring(lineStart, lineEnd).trim()));
            }
        } catch (IOException e) {
            if (diagnostics != null) {
                diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.UNREADABLE_TREE,
                        htmlFile, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink.reasonOf(e));
            }
        }
    }

    private int lineNumber(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private int column(String content, int offset) {
        int newline = content.lastIndexOf('\n', offset - 1);
        return offset - newline;
    }

    public void scanFile(Path htmlFile, List<HtlUsage> collector) {
        scanFile(htmlFile, collector, new ArrayList<>());
    }

    private boolean isNotIgnored(Path path) {
        String s = path.toString().replace('\\', '/');
        return !s.contains("/target/") && !s.contains("/node_modules/") && !s.contains("/.git/");
    }
}
