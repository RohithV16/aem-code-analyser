package io.github.rohithv16.aem.analyser.core.fix;

import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Automated safe remediation engine for deterministic code smells (e.g. S1128, S1125, S4719).
 */
public final class AutoFixEngine {

    public record FixRecord(String filePath, String ruleId, String description, int line) {}

    public record AutoFixSummary(
            int filesModified,
            int fixesApplied,
            List<FixRecord> records,
            boolean dryRun) {

        public boolean hasFixes() {
            return fixesApplied > 0;
        }
    }

    public AutoFixSummary fix(Path projectRoot, List<Violation> violations, boolean dryRun) {
        if (projectRoot == null || violations == null || violations.isEmpty()) {
            return new AutoFixSummary(0, 0, List.of(), dryRun);
        }

        // Group violations by target file path
        Set<String> candidateFiles = new HashSet<>();
        for (Violation v : violations) {
            String rule = v.ruleId();
            if (rule.equalsIgnoreCase("java:S1128") || rule.equalsIgnoreCase("S1128")
                    || rule.equalsIgnoreCase("java:S1125") || rule.equalsIgnoreCase("S1125")
                    || rule.equalsIgnoreCase("java:S4719") || rule.equalsIgnoreCase("S4719")) {
                if (v.filePath() != null && !v.filePath().isBlank()) {
                    candidateFiles.add(v.filePath());
                }
            }
        }

        List<FixRecord> fixRecords = new ArrayList<>();
        int modifiedCount = 0;

        for (String relPath : candidateFiles) {
            Path file = projectRoot.resolve(relPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String original = Files.readString(file, StandardCharsets.UTF_8);
                String current = original;

                // 1. Fix S1128: Duplicate imports
                var importFix = fixDuplicateImports(current, relPath);
                current = importFix.content();
                fixRecords.addAll(importFix.records());

                // 2. Fix S1125: Redundant boolean literal == true
                var booleanFix = fixRedundantBoolean(current, relPath);
                current = booleanFix.content();
                fixRecords.addAll(booleanFix.records());

                // 3. Fix S4719: StandardCharsets constants
                var charsetFix = fixStandardCharsets(current, relPath);
                current = charsetFix.content();
                fixRecords.addAll(charsetFix.records());

                if (!current.equals(original)) {
                    modifiedCount++;
                    if (!dryRun) {
                        Files.writeString(file, current, StandardCharsets.UTF_8);
                    }
                }
            } catch (IOException ignored) {
                // Ignore unreadable files
            }
        }

        return new AutoFixSummary(modifiedCount, fixRecords.size(), List.copyOf(fixRecords), dryRun);
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
            if (importPattern.matcher(trimmed).matches()) {
                if (!seenImports.add(trimmed)) {
                    // Duplicate import found: drop line
                    records.add(new FixRecord(filePath, "java:S1128", "Removed duplicated import: " + trimmed, i + 1));
                    continue;
                }
            }
            resultLines.add(line);
        }

        return new TransformResult(String.join(content.contains("\r\n") ? "\r\n" : "\n", resultLines), records);
    }

    private TransformResult fixRedundantBoolean(String content, String filePath) {
        List<FixRecord> records = new ArrayList<>();
        String result = content;

        if (result.contains(" == true")) {
            records.add(new FixRecord(filePath, "java:S1125", "Removed redundant '== true'", -1));
            result = result.replaceAll("\\s*==\\s*true\\b", "");
        }

        return new TransformResult(result, records);
    }

    private TransformResult fixStandardCharsets(String content, String filePath) {
        List<FixRecord> records = new ArrayList<>();
        String result = content;

        boolean modified = false;
        if (result.contains(".getBytes(\"UTF-8\")")) {
            result = result.replace(".getBytes(\"UTF-8\")", ".getBytes(StandardCharsets.UTF_8)");
            records.add(new FixRecord(filePath, "java:S4719", "Replaced \"UTF-8\" with StandardCharsets.UTF_8 in .getBytes()", -1));
            modified = true;
        }
        if (result.contains(".getBytes(\"utf-8\")")) {
            result = result.replace(".getBytes(\"utf-8\")", ".getBytes(StandardCharsets.UTF_8)");
            records.add(new FixRecord(filePath, "java:S4719", "Replaced \"utf-8\" with StandardCharsets.UTF_8 in .getBytes()", -1));
            modified = true;
        }

        if (modified && !result.contains("import java.nio.charset.StandardCharsets;")) {
            // Add import after package declaration or first import
            int pkgIdx = result.indexOf("package ");
            if (pkgIdx >= 0) {
                int semi = result.indexOf(";", pkgIdx);
                if (semi >= 0) {
                    result = result.substring(0, semi + 1) + "\n\nimport java.nio.charset.StandardCharsets;" + result.substring(semi + 1);
                }
            }
        }

        return new TransformResult(result, records);
    }
}
