package io.github.rohithv16.aem.analyser.core.fix;

import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automated safe remediation engine for deterministic code smells (e.g. S1128, S1125, S4719).
 *
 * <p>Each transform runs only for a file that actually has the corresponding violation, never
 * rewrites text inside string literals or comments, and records a fix only after the file was
 * written successfully.
 */
public final class AutoFixEngine {

    private static final String S1128 = "java:S1128";
    private static final String S1125 = "java:S1125";
    private static final String S4719 = "java:S4719";

    public record FixRecord(String filePath, String ruleId, String description, int line) {}

    public record AutoFixSummary(
            int filesModified,
            int fixesApplied,
            List<FixRecord> records,
            boolean dryRun,
            List<String> writeFailures) {

        public AutoFixSummary(int filesModified, int fixesApplied, List<FixRecord> records, boolean dryRun) {
            this(filesModified, fixesApplied, records, dryRun, List.of());
        }

        public boolean hasFixes() {
            return fixesApplied > 0;
        }
    }

    public AutoFixSummary fix(Path projectRoot, List<Violation> violations, boolean dryRun) {
        if (projectRoot == null || violations == null || violations.isEmpty()) {
            return new AutoFixSummary(0, 0, List.of(), dryRun);
        }

        // Group the fixable rule ids by target file so a file only gets the transforms that apply.
        Map<String, Set<String>> rulesByFile = new LinkedHashMap<>();
        for (Violation violation : violations) {
            String rule = normalise(violation.ruleId());
            if (!S1128.equals(rule) && !S1125.equals(rule) && !S4719.equals(rule)) {
                continue;
            }
            if (violation.filePath() == null || violation.filePath().isBlank()) {
                continue;
            }
            rulesByFile.computeIfAbsent(violation.filePath(), key -> new LinkedHashSet<>()).add(rule);
        }

        List<FixRecord> fixRecords = new ArrayList<>();
        List<String> writeFailures = new ArrayList<>();
        int modifiedCount = 0;

        for (Map.Entry<String, Set<String>> entry : rulesByFile.entrySet()) {
            String relPath = entry.getKey();
            Set<String> applicable = entry.getValue();
            Path file = projectRoot.resolve(relPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String original = Files.readString(file, StandardCharsets.UTF_8);
                String current = original;
                List<FixRecord> fileRecords = new ArrayList<>();

                if (applicable.contains(S1128)) {
                    var importFix = fixDuplicateImports(current, relPath);
                    current = importFix.content();
                    fileRecords.addAll(importFix.records());
                }
                if (applicable.contains(S1125)) {
                    var booleanFix = fixRedundantBoolean(current, relPath);
                    current = booleanFix.content();
                    fileRecords.addAll(booleanFix.records());
                }
                if (applicable.contains(S4719)) {
                    var charsetFix = fixStandardCharsets(current, relPath);
                    current = charsetFix.content();
                    fileRecords.addAll(charsetFix.records());
                }

                if (!current.equals(original) && !fileRecords.isEmpty()) {
                    if (dryRun) {
                        modifiedCount++;
                        fixRecords.addAll(fileRecords);
                    } else {
                        try {
                            Files.writeString(file, current, StandardCharsets.UTF_8);
                            modifiedCount++;
                            fixRecords.addAll(fileRecords);
                        } catch (IOException writeFailure) {
                            writeFailures.add(relPath + ": " + writeFailure.getMessage());
                        }
                    }
                }
            } catch (IOException unreadable) {
                writeFailures.add(relPath + ": " + unreadable.getMessage());
            }
        }

        return new AutoFixSummary(modifiedCount, fixRecords.size(), List.copyOf(fixRecords), dryRun,
                List.copyOf(writeFailures));
    }

    private static String normalise(String ruleId) {
        if (ruleId == null) {
            return "";
        }
        String trimmed = ruleId.trim();
        return trimmed.startsWith("java:") || trimmed.isEmpty() ? trimmed : "java:" + trimmed;
    }

    record TransformResult(String content, List<FixRecord> records) {}

    private TransformResult fixDuplicateImports(String content, String filePath) {
        String[] lines = content.split("\r?\n", -1);
        List<String> resultLines = new ArrayList<>(lines.length);
        List<FixRecord> records = new ArrayList<>();
        Set<String> seenImports = new HashSet<>();

        Pattern importPattern = Pattern.compile("^\\s*import\\s+(?:static\\s+)?[a-zA-Z0-9_.]+\\s*;");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (importPattern.matcher(trimmed).matches() && !seenImports.add(trimmed)) {
                records.add(new FixRecord(filePath, S1128, "Removed duplicated import: " + trimmed, i + 1));
                continue;
            }
            resultLines.add(line);
        }

        return new TransformResult(String.join(content.contains("\r\n") ? "\r\n" : "\n", resultLines), records);
    }

    /**
     * Removes redundant {@code == true} comparisons. Masking preserves character offsets, so a match
     * inside a string literal or comment can never be rewritten.
     */
    private TransformResult fixRedundantBoolean(String content, String filePath) {
        List<FixRecord> records = new ArrayList<>();
        String[] lines = content.split("\r?\n", -1);
        Pattern redundant = Pattern.compile("\\s*==\\s*true\\b");
        StringBuilder result = new StringBuilder(content.length());
        String separator = content.contains("\r\n") ? "\r\n" : "\n";

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher matcher = redundant.matcher(mask(line));
            List<int[]> spans = new ArrayList<>();
            while (matcher.find()) {
                spans.add(new int[]{matcher.start(), matcher.end()});
            }
            String rewritten = line;
            for (int s = spans.size() - 1; s >= 0; s--) {
                int[] span = spans.get(s);
                rewritten = rewritten.substring(0, span[0]) + rewritten.substring(span[1]);
            }
            if (!spans.isEmpty() && !rewritten.equals(line)) {
                records.add(new FixRecord(filePath, S1125, "Removed redundant '== true' comparison", i + 1));
            }
            result.append(rewritten);
            if (i < lines.length - 1) {
                result.append(separator);
            }
        }

        return new TransformResult(result.toString(), records);
    }

    /**
     * Blanks out string literals, char literals and comments while preserving length, so regex
     * matches on the masked text map 1:1 onto the real source.
     */
    static String mask(String line) {
        StringBuilder masked = new StringBuilder(line.length());
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
            if (inLineComment) {
                masked.append(' ');
            } else if (inBlockComment) {
                masked.append(' ');
                if (c == '*' && next == '/') {
                    masked.append(' ');
                    i++;
                    inBlockComment = false;
                }
            } else if (inString) {
                masked.append(' ');
                if (c == '\\' && next != '\0') {
                    masked.append(' ');
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (inChar) {
                masked.append(' ');
                if (c == '\\' && next != '\0') {
                    masked.append(' ');
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
            } else if (c == '/' && next == '/') {
                masked.append(' ');
                inLineComment = true;
            } else if (c == '/' && next == '*') {
                masked.append(' ');
                inBlockComment = true;
            } else if (c == '"') {
                masked.append(' ');
                inString = true;
            } else if (c == '\'') {
                masked.append(' ');
                inChar = true;
            } else {
                masked.append(c);
            }
        }
        return masked.toString();
    }

    private TransformResult fixStandardCharsets(String content, String filePath) {
        List<FixRecord> records = new ArrayList<>();
        String result = content;

        for (String literal : List.of("\"UTF-8\"", "\"utf-8\"")) {
            String call = ".getBytes(" + literal + ")";
            if (result.contains(call)) {
                result = result.replace(call, ".getBytes(StandardCharsets.UTF_8)");
                records.add(new FixRecord(filePath, S4719,
                        "Replaced " + literal + " with StandardCharsets.UTF_8 in .getBytes()", -1));
            }
        }

        if (!records.isEmpty() && !result.contains("import java.nio.charset.StandardCharsets;")) {
            result = insertStandardCharsetsImport(result);
        }

        return new TransformResult(result, records);
    }

    /**
     * Inserts the StandardCharsets import after the package declaration, or at the top of the file
     * when there is none - leaving it out produced uncompilable code.
     */
    private String insertStandardCharsetsImport(String content) {
        String importLine = "import java.nio.charset.StandardCharsets;";
        int packageIndex = content.indexOf("package ");
        if (packageIndex >= 0) {
            int semicolon = content.indexOf(';', packageIndex);
            if (semicolon >= 0) {
                return content.substring(0, semicolon + 1) + "\n\n" + importLine
                        + content.substring(semicolon + 1);
            }
        }
        return importLine + "\n\n" + content;
    }
}
