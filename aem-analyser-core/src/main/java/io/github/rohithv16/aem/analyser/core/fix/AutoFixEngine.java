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
 * rewrites text inside string literals or comments, and produces a list of {@link Edit}s — the exact
 * character ranges and replacement text. The same edits are what {@code --fix} writes and what the
 * SARIF exporter publishes as {@code fixes}, so a code-scanning consumer applying them lands on
 * byte-identical source.</p>
 */
public final class AutoFixEngine {

    public static final String S1128 = "java:S1128";
    public static final String S1125 = "java:S1125";
    public static final String S4719 = "java:S4719";

    /**
     * One machine-applicable text replacement in a source file.
     *
     * @param startOffset   inclusive character offset in the original file
     * @param endOffset     exclusive character offset; equal to {@code startOffset} for an insertion
     * @param insertedText  replacement text ({@code ""} for a pure deletion)
     * @param line          1-based line the edit starts on, for human-facing messages
     */
    public record Edit(String filePath, String ruleId, int startOffset, int endOffset,
                       String insertedText, int line, String description) {

        public boolean deletion() {
            return insertedText.isEmpty();
        }

        public boolean insertion() {
            return startOffset == endOffset;
        }
    }

    public record FixRecord(String filePath, String ruleId, String description, int line) {}

    /** The edits for one file, plus the content they were computed against. */
    public record FilePlan(String filePath, String originalContent, List<Edit> edits) {

        public FilePlan {
            edits = List.copyOf(edits);
        }

        /** Applying every edit reproduces exactly what {@code --fix} writes. */
        public String fixedContent() {
            return applyEdits(originalContent, edits);
        }
    }

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

    /** True when a rule id has a deterministic transform, e.g. {@code S1128} or {@code java:S1128}. */
    public static boolean isMachineFixable(String ruleId) {
        String rule = canonicalRuleId(ruleId);
        return S1128.equals(rule) || S1125.equals(rule) || S4719.equals(rule);
    }

    /** The {@code java:}-prefixed form a {@link Edit} carries, so callers can match edit to finding. */
    public static String canonicalRuleId(String ruleId) {
        return normalise(ruleId);
    }

    private record PlanResult(List<FilePlan> plans, List<String> failures) {}

    public AutoFixSummary fix(Path projectRoot, List<Violation> violations, boolean dryRun) {
        PlanResult planned = planDetailed(projectRoot, violations);
        List<FixRecord> fixRecords = new ArrayList<>();
        List<String> writeFailures = new ArrayList<>(planned.failures());
        int modifiedCount = 0;

        for (FilePlan plan : planned.plans()) {
            String fixed = plan.fixedContent();
            if (fixed.equals(plan.originalContent())) {
                continue;
            }
            if (!dryRun) {
                try {
                    Files.writeString(projectRoot.resolve(plan.filePath()).toAbsolutePath().normalize(),
                            fixed, StandardCharsets.UTF_8);
                } catch (IOException writeFailure) {
                    writeFailures.add(plan.filePath() + ": " + writeFailure.getMessage());
                    continue;
                }
            }
            modifiedCount++;
            for (Edit edit : plan.edits()) {
                fixRecords.add(new FixRecord(edit.filePath(), edit.ruleId(), edit.description(), edit.line()));
            }
        }

        return new AutoFixSummary(modifiedCount, fixRecords.size(), List.copyOf(fixRecords), dryRun,
                List.copyOf(writeFailures));
    }

    /**
     * Computes the edits for every fixable finding without touching disk: the shared input for
     * {@code --fix} and for the report's machine-applicable fixes.
     */
    public List<FilePlan> plan(Path projectRoot, List<Violation> violations) {
        return planDetailed(projectRoot, violations).plans();
    }

    private PlanResult planDetailed(Path projectRoot, List<Violation> violations) {
        if (projectRoot == null || violations == null || violations.isEmpty()) {
            return new PlanResult(List.of(), List.of());
        }

        // Group the fixable rule ids by target file so a file only gets the transforms that apply.
        Map<String, Set<String>> rulesByFile = new LinkedHashMap<>();
        for (Violation violation : violations) {
            String rule = normalise(violation.ruleId());
            if (!isMachineFixable(rule)) {
                continue;
            }
            if (violation.filePath() == null || violation.filePath().isBlank()) {
                continue;
            }
            rulesByFile.computeIfAbsent(violation.filePath(), key -> new LinkedHashSet<>()).add(rule);
        }

        List<FilePlan> plans = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : rulesByFile.entrySet()) {
            String relPath = entry.getKey();
            Path file = projectRoot.resolve(relPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String original = Files.readString(file, StandardCharsets.UTF_8);
                List<Edit> edits = editsFor(original, relPath, entry.getValue());
                if (!edits.isEmpty()) {
                    plans.add(new FilePlan(relPath, original, edits));
                }
            } catch (IOException unreadable) {
                failures.add(relPath + ": " + unreadable.getMessage());
            }
        }
        return new PlanResult(List.copyOf(plans), List.copyOf(failures));
    }

    private static List<Edit> editsFor(String content, String filePath, Set<String> applicable) {
        String[] lines = content.split("\r?\n", -1);
        int[] lineStarts = new int[lines.length];
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            lineStarts[i] = offset;
            offset += lines[i].length();
            if (i < lines.length - 1) {
                offset += content.startsWith("\r\n", offset) ? 2 : 1;
            }
        }
        List<Edit> edits = new ArrayList<>();
        if (applicable.contains(S1128)) {
            edits.addAll(duplicateImportEdits(content, lines, lineStarts, filePath));
        }
        if (applicable.contains(S1125)) {
            edits.addAll(redundantBooleanEdits(lines, lineStarts, filePath));
        }
        if (applicable.contains(S4719)) {
            edits.addAll(standardCharsetsEdits(content, lines, lineStarts, filePath));
        }
        return edits;
    }

    /** A deletion of one whole line, including its terminator, so no blank line is left behind. */
    private static Edit deleteLine(String content, String[] lines, int[] lineStarts, String filePath,
                                   String ruleId, int lineIndex, String description) {
        int start = lineStarts[lineIndex];
        int end = start + lines[lineIndex].length();
        if (end < content.length()) {
            end += content.startsWith("\r\n", end) ? 2 : 1;
        } else if (start > 0) {
            // Last line without a trailing newline: take the preceding terminator with it, so the
            // join() semantics of the old line-array implementation are preserved byte for byte.
            start -= start >= 2 && content.charAt(start - 2) == '\r' ? 2 : 1;
        }
        return new Edit(filePath, ruleId, start, end, "", lineIndex + 1, description);
    }

    private static List<Edit> duplicateImportEdits(String content, String[] lines, int[] lineStarts,
                                                   String filePath) {
        List<Edit> edits = new ArrayList<>();
        Set<String> seenImports = new HashSet<>();
        Pattern importPattern = Pattern.compile("^\\s*import\\s+(?:static\\s+)?[a-zA-Z0-9_.]+\\s*;");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (importPattern.matcher(trimmed).matches() && !seenImports.add(trimmed)) {
                edits.add(deleteLine(content, lines, lineStarts, filePath, S1128, i,
                        "Removed duplicated import: " + trimmed));
            }
        }
        return edits;
    }

    /**
     * Removes redundant {@code == true} comparisons. Masking preserves character offsets, so a match
     * inside a string literal or comment can never be rewritten.
     */
    private static List<Edit> redundantBooleanEdits(String[] lines, int[] lineStarts, String filePath) {
        List<Edit> edits = new ArrayList<>();
        Pattern redundant = Pattern.compile("\\s*==\\s*true\\b");
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = redundant.matcher(mask(lines[i]));
            while (matcher.find()) {
                edits.add(new Edit(filePath, S1125, lineStarts[i] + matcher.start(),
                        lineStarts[i] + matcher.end(), "", i + 1,
                        "Removed redundant '== true' comparison"));
            }
        }
        return edits;
    }

    /**
     * Replaces {@code "UTF-8"} literals passed to {@code getBytes} with
     * {@code StandardCharsets.UTF_8}, and adds the import when it is missing.
     */
    private static List<Edit> standardCharsetsEdits(String content, String[] lines, int[] lineStarts,
                                                    String filePath) {
        List<Edit> edits = new ArrayList<>();
        Pattern literal = Pattern.compile("\\.getBytes\\(\"(?:UTF|utf)-8\"\\)");
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = literal.matcher(lines[i]);
            while (matcher.find()) {
                edits.add(new Edit(filePath, S4719, lineStarts[i] + matcher.start(),
                        lineStarts[i] + matcher.end(), ".getBytes(StandardCharsets.UTF_8)", i + 1,
                        "Replaced a \"UTF-8\" literal with StandardCharsets.UTF_8 in .getBytes()"));
            }
        }
        if (!edits.isEmpty() && !content.contains("import java.nio.charset.StandardCharsets;")) {
            edits.add(standardCharsetsImport(content, lines, lineStarts, filePath));
        }
        return edits;
    }

    /**
     * Inserts {@code import java.nio.charset.StandardCharsets;} after the package declaration, or at
     * the top of the file when there is none — leaving it out produced uncompilable code.
     *
     * <p>Expressed as a one-character replacement of the terminator after the package statement (or
     * of the first character for a file without a package) instead of a zero-width insertion: a
     * SARIF replacement that deletes nothing does not say where the content goes, and consumers
     * render and apply it unreliably.</p>
     */
    private static Edit standardCharsetsImport(String content, String[] lines, int[] lineStarts,
                                               String filePath) {
        String importLine = "import java.nio.charset.StandardCharsets;";
        int packageIndex = content.indexOf("package ");
        if (packageIndex >= 0) {
            int semicolon = content.indexOf(';', packageIndex);
            if (semicolon >= 0) {
                int line = 1;
                while (line < lineStarts.length && lineStarts[line] <= semicolon) {
                    line++;
                }
                int lineEnd = lineStarts[line - 1] + lines[line - 1].length();
                if (lineEnd < content.length()) {
                    int separatorLength = content.startsWith("\r\n", lineEnd) ? 2 : 1;
                    String separator = content.substring(lineEnd, lineEnd + separatorLength);
                    return new Edit(filePath, S4719, lineEnd, lineEnd + separatorLength,
                            "\n\n" + importLine + separator, line, "Added " + importLine);
                }
                if (lineEnd > 0) {
                    // No trailing newline: gain a real region by re-emitting the last character.
                    String last = content.substring(lineEnd - 1, lineEnd);
                    return new Edit(filePath, S4719, lineEnd - 1, lineEnd,
                            last + "\n\n" + importLine, line, "Added " + importLine);
                }
            }
        }
        if (!content.isEmpty()) {
            int end = content.offsetByCodePoints(0, 1);
            return new Edit(filePath, S4719, 0, end,
                    importLine + "\n\n" + content.substring(0, end), 1, "Added " + importLine);
        }
        return new Edit(filePath, S4719, 0, 0, importLine + "\n\n", 1, "Added " + importLine);
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

    /**
     * Applies edits to the original text. Edits are applied in offset order; overlapping edits are a
     * programming defect in a transform and fail loudly rather than silently corrupting the source.
     */
    public static String applyEdits(String content, List<Edit> edits) {
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(java.util.Comparator.comparingInt(Edit::startOffset)
                .thenComparingInt(Edit::endOffset));
        StringBuilder result = new StringBuilder(content.length() + 64);
        int cursor = 0;
        for (Edit edit : ordered) {
            if (edit.startOffset() < cursor || edit.endOffset() < edit.startOffset()
                    || edit.endOffset() > content.length()) {
                throw new IllegalStateException("Overlapping or out-of-range fix edit in "
                        + edit.filePath() + " at line " + edit.line());
            }
            result.append(content, cursor, edit.startOffset());
            result.append(edit.insertedText());
            cursor = edit.endOffset();
        }
        result.append(content, cursor, content.length());
        return result.toString();
    }

    private static String normalise(String ruleId) {
        if (ruleId == null) {
            return "";
        }
        String trimmed = ruleId.trim();
        return trimmed.startsWith("java:") || trimmed.isEmpty() ? trimmed : "java:" + trimmed;
    }
}
