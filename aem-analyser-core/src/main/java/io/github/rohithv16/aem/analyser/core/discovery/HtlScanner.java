package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.htl.HtlIncludeRef;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlResourceRef;
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
 * Discovers and extracts data-sly-use usages, template-composition references and security risks
 * from HTL (.html) templates.
 *
 * <p>All attributes are read in one pass per file so that rules never re-walk the tree: the use-API
 * list feeds AEM-DEAD-004, {@code data-sly-include} feeds AEM-DEAD-007 and the {@code resourceType}
 * option of {@code data-sly-resource} feeds AEM-COMP-002.</p>
 */
public final class HtlScanner {

    public record HtlScanResult(List<HtlUsage> usages,
                                List<HtlUnsafeContextFinding> unsafeContexts,
                                List<HtlIncludeRef> includes,
                                List<HtlResourceRef> resources) {

        /** Compatibility for callers that only care about use-API usages and unsafe contexts. */
        public HtlScanResult(List<HtlUsage> usages, List<HtlUnsafeContextFinding> unsafeContexts) {
            this(usages, unsafeContexts, List.of(), List.of());
        }
    }

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /** Same expression but across line breaks (multi-line HTL attributes). */
    private static final Pattern SLY_USE_PATTERN_MULTILINE = Pattern.compile(
            "data-sly-use(?:\\.([a-zA-Z0-9_$-]+))?\\s*=\\s*([\"'])(.*?)\\2",
            Pattern.DOTALL
    );

    /**
     * A string-literal expression: {@code ${'com.acme.HeroModel'}} or, with the documented
     * option syntax, {@code ${'com.acme.HeroModel' @ title=properties.title, index=1}}.
     * The options are not part of the class name, so only the literal is captured.
     */
    private static final Pattern EXPR_TARGET_PATTERN = Pattern.compile(
            "^\\s*\\$\\{\\s*[\"']([^\"']+)[\"']\\s*(?:@[^}]*)?\\}\\s*$"
    );

    private static final Pattern UNSAFE_CONTEXT_PATTERN = Pattern.compile(
            "@\\s*context\\s*=\\s*['\"]unsafe['\"]",
            Pattern.CASE_INSENSITIVE
    );

    /** {@code data-sly-include="path.html"} / {@code data-sly-include="${'path.html'}"}. */
    private static final Pattern SLY_INCLUDE_PATTERN = Pattern.compile(
            "data-sly-include\\s*=\\s*([\"'])(.*?)\\1",
            Pattern.DOTALL
    );

    /** {@code data-sly-resource="..."}; the value may be a path or an expression with options. */
    private static final Pattern SLY_RESOURCE_PATTERN = Pattern.compile(
            "data-sly-resource\\s*=\\s*([\"'])(.*?)\\1",
            Pattern.DOTALL
    );

    /** The literal {@code resourceType='...'} option inside a data-sly-resource expression. */
    private static final Pattern RESOURCE_TYPE_OPTION_PATTERN = Pattern.compile(
            "resourceType\\s*=\\s*([\"'])([^\"']+)\\1"
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
        List<HtlIncludeRef> includes = new ArrayList<>();
        List<HtlResourceRef> resources = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return new HtlScanResult(usages, unsafeContexts, includes, resources);
        }

        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".html"))
                    .filter(this::isNotIgnored)
                    .forEach(htmlFile -> scanFile(htmlFile, usages, unsafeContexts, includes, resources,
                            diagnostics, maxFileSizeBytes));
        } catch (IOException e) {
            if (diagnostics != null) {
                diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.UNREADABLE_TREE,
                        directory, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink.reasonOf(e));
            }
        }

        return new HtlScanResult(usages, unsafeContexts, includes, resources);
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
        scanFile(htmlFile, usagesCollector, unsafeCollector, new ArrayList<>(), new ArrayList<>(),
                diagnostics, maxFileSizeBytes);
    }

    public void scanFile(Path htmlFile, List<HtlUsage> usagesCollector,
                         List<HtlUnsafeContextFinding> unsafeCollector,
                         List<HtlIncludeRef> includeCollector,
                         List<HtlResourceRef> resourceCollector,
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

            // 3. data-sly-include template composition: literal, relative targets only.
            Matcher includeMatcher = SLY_INCLUDE_PATTERN.matcher(content);
            while (includeMatcher.find()) {
                String target = literalTarget(includeMatcher.group(2));
                if (target != null) {
                    includeCollector.add(new HtlIncludeRef(htmlFile,
                            lineNumber(content, includeMatcher.start()),
                            column(content, includeMatcher.start()), target));
                }
            }

            // 4. data-sly-resource with an explicit literal resourceType option.
            Matcher resourceMatcher = SLY_RESOURCE_PATTERN.matcher(content);
            while (resourceMatcher.find()) {
                Matcher optionMatcher = RESOURCE_TYPE_OPTION_PATTERN.matcher(resourceMatcher.group(2));
                if (!optionMatcher.find()) {
                    continue;
                }
                String resourceType = literalTarget(optionMatcher.group(2));
                if (resourceType != null) {
                    resourceCollector.add(new HtlResourceRef(htmlFile,
                            lineNumber(content, resourceMatcher.start()),
                            column(content, resourceMatcher.start()), resourceType));
                }
            }
        } catch (IOException e) {
            if (diagnostics != null) {
                diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.UNREADABLE_TREE,
                        htmlFile, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink.reasonOf(e));
            }
        }
    }

    /**
     * @return the literal value of an attribute, or {@code null} when it is computed.
     *
     * <p>{@code ${'nav.html'}} is a literal expression and yields {@code nav.html}; anything that
     * still contains expression syntax after that unwrapping ({@code ${model.tpl}},
     * {@code ${'a.html' @ opt=x}}) cannot be resolved statically and yields {@code null}.</p>
     */
    private static String literalTarget(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        Matcher exprMatcher = EXPR_TARGET_PATTERN.matcher(value);
        if (exprMatcher.matches()) {
            value = exprMatcher.group(1).strip();
        }
        if (value.isEmpty()) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '$' || c == '{' || c == '}' || c == '@' || c == '(' || c == ')'
                    || Character.isWhitespace(c)) {
                return null;
            }
        }
        return value;
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
