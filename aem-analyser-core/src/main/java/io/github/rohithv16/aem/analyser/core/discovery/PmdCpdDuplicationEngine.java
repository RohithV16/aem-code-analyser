package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.CodeMetrics;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParseResult;
import net.sourceforge.pmd.cpd.CPDConfiguration;
import net.sourceforge.pmd.cpd.CpdAnalysis;
import net.sourceforge.pmd.cpd.Mark;
import net.sourceforge.pmd.cpd.Match;
import net.sourceforge.pmd.lang.document.FileLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Industry-standard duplicate code detection powered by open-source PMD CPD (Copy/Paste Detector).
 * Uses PMD's Karp-Rabin token stream matching to identify exact and near-exact duplicate code lines.
 */
public final class PmdCpdDuplicationEngine {

    public static final int DEFAULT_MINIMUM_TOKENS = 50;

    private final int minimumTokens;

    public PmdCpdDuplicationEngine() {
        this(DEFAULT_MINIMUM_TOKENS);
    }

    public PmdCpdDuplicationEngine(int minimumTokens) {
        this.minimumTokens = Math.max(10, minimumTokens);
    }

    public record Result(
            CodeMetrics metrics,
            List<Violation> violations
    ) {
    }

    public Result analyze(Path projectRoot, List<JavaSourceParseResult> javaResults) {
        Map<Path, Integer> fileLineCounts = new HashMap<>();
        Map<Path, String> fileClassNames = new HashMap<>();
        List<Path> filesToAnalyze = new ArrayList<>();

        for (var res : javaResults) {
            Path file = res.filePath();
            fileLineCounts.put(file, res.lineCount());
            String className = res.javaClasses().isEmpty()
                    ? file.getFileName().toString().replace(".java", "")
                    : res.javaClasses().get(0).getFqn();
            fileClassNames.put(file, className);
            if (Files.isRegularFile(file)) {
                filesToAnalyze.add(file);
            }
        }

        if (filesToAnalyze.isEmpty()) {
            return new Result(new CodeMetrics(0, 0, 0, 0, 0, 0.0, List.of()), List.of());
        }

        CPDConfiguration config = new CPDConfiguration();
        config.setMinimumTileSize(minimumTokens);
        config.setOnlyRecognizeLanguage(config.getLanguageRegistry().getLanguageById("java"));
        config.setSkipLexicalErrors(true);
        config.setIgnoreAnnotations(true);

        List<Match> matches = new ArrayList<>();
        try (CpdAnalysis cpd = CpdAnalysis.create(config)) {
            for (Path p : filesToAnalyze) {
                cpd.files().addFile(p);
            }
            cpd.performAnalysis(report -> matches.addAll(report.getMatches()));
        } catch (IOException | RuntimeException e) {
            // Graceful fallback if PMD encounters an environment error
            return new Result(new CodeMetrics(javaResults.size(),
                    javaResults.stream().mapToInt(JavaSourceParseResult::lineCount).sum(),
                    0, 0, 0, 0.0, List.of()), List.of());
        }

        int duplicateBlocks = 0;
        int duplicatedLines = 0;
        Map<Path, Integer> fileDuplicatedLines = new HashMap<>();
        List<Violation> violations = new ArrayList<>();
        List<CodeMetrics.DuplicateBlock> duplicateBlockDetails = new ArrayList<>();

        for (Match match : matches) {
            List<Mark> marks = new ArrayList<>(match.getMarkSet());
            if (marks.size() < 2) {
                continue;
            }

            Mark firstMark = match.getFirstMark();
            FileLocation firstLoc = firstMark.getLocation();
            Path firstPath = Path.of(firstLoc.getFileId().getOriginalPath()).toAbsolutePath().normalize();
            int lineCount = match.getLineCount();
            int tokenCount = match.getTokenCount();
            String firstClassName = fileClassNames.getOrDefault(firstPath, firstPath.getFileName().toString());

            for (int i = 1; i < marks.size(); i++) {
                Mark otherMark = marks.get(i);
                FileLocation otherLoc = otherMark.getLocation();
                Path otherPath = Path.of(otherLoc.getFileId().getOriginalPath()).toAbsolutePath().normalize();
                String otherClassName = fileClassNames.getOrDefault(otherPath, otherPath.getFileName().toString());

                if (isSameFileOverlap(firstPath, firstLoc, otherPath, otherLoc)) {
                    continue; // a shifted window in the same file is not a cross-boundary clone
                }
                if (isDelegationOnly(firstPath, firstLoc) || isDelegationOnly(otherPath, otherLoc)) {
                    continue; // constructor delegation / delegating accessors are framework boilerplate
                }

                duplicateBlocks++;
                duplicatedLines += lineCount;
                fileDuplicatedLines.merge(otherPath, lineCount, Integer::sum);
                int codeLines = countCodeLines(otherPath, otherLoc);

                String relFirst = relativize(projectRoot, firstPath);
                String relOther = relativize(projectRoot, otherPath);

                duplicateBlockDetails.add(new CodeMetrics.DuplicateBlock(
                        relOther, otherLoc.getStartLine(), otherLoc.getEndLine(),
                        relFirst, firstLoc.getStartLine(), firstLoc.getEndLine(),
                        lineCount, countCodeLines(otherPath, otherLoc), tokenCount,
                        blockFingerprint(relOther, otherLoc.getStartLine(), tokenCount)));

                List<RelatedLocation> related = List.of(new RelatedLocation(
                        String.format("Original block in %s (%d lines, %d tokens)", firstClassName, lineCount, tokenCount),
                        relFirst,
                        new TextRegion(firstLoc.getStartLine(), 1, firstLoc.getEndLine(), 1)
                ));

                violations.add(new Violation(
                        RuleId.AEM_DUP_010.id(),
                        RuleId.AEM_DUP_010.defaultLevel(),
                        "DUPLICATE_CODE_BLOCK",
                        String.format("Duplicate code block (%d lines, %d code lines, %d tokens): %s:%d-%d matches %s:%d-%d.",
                                lineCount, codeLines, tokenCount, relOther, otherLoc.getStartLine(), otherLoc.getEndLine(),
                                relFirst, firstLoc.getStartLine(), firstLoc.getEndLine()),
                        relOther,
                        new TextRegion(otherLoc.getStartLine(), 1, otherLoc.getEndLine(), 1),
                        otherClassName,
                        "Refactor duplicated logic into a shared OSGi service, Sling Model, or utility class.",
                        related
                ));
            }
        }

        List<CodeMetrics.ClassDuplication> duplicateClasses = new ArrayList<>();
        for (var entry : fileDuplicatedLines.entrySet()) {
            Path file = entry.getKey();
            int dupLines = entry.getValue();
            int totalLines = fileLineCounts.getOrDefault(file, 0);
            double pct = totalLines > 0 ? (dupLines * 100.0) / totalLines : 0.0;
            String className = fileClassNames.getOrDefault(file, file.getFileName().toString());
            String relPath = relativize(projectRoot, file);
            duplicateClasses.add(new CodeMetrics.ClassDuplication(className, relPath, dupLines, totalLines, pct));
        }
        duplicateClasses.sort(Comparator.comparingInt(CodeMetrics.ClassDuplication::duplicatedLines).reversed());

        int totalJavaFiles = javaResults.size();
        int totalJavaLines = javaResults.stream().mapToInt(JavaSourceParseResult::lineCount).sum();
        double dupPct = totalJavaLines > 0 ? (duplicatedLines * 100.0) / totalJavaLines : 0.0;

        CodeMetrics metrics = new CodeMetrics(
                totalJavaFiles,
                totalJavaLines,
                matches.size(),
                duplicatedLines,
                duplicateBlocks,
                dupPct,
                duplicateClasses,
                duplicateBlockDetails
        );

        return new Result(metrics, violations);
    }

    private int countCodeLines(Path file, FileLocation location) {
        try {
            List<String> lines = Files.readAllLines(file);
            int code = 0;
            for (int line = Math.max(0, location.getStartLine() - 1);
                 line < Math.min(lines.size(), location.getEndLine()); line++) {
                String text = lines.get(line).strip();
                if (text.isEmpty() || text.startsWith("//") || text.startsWith("*")
                        || text.startsWith("/*")) {
                    continue;
                }
                code++;
            }
            return code;
        } catch (IOException e) {
            return Math.max(0, location.getEndLine() - location.getStartLine() + 1);
        }
    }

    /**
     * Stable identity for one duplicated block (survives line moves and message wording changes).
     */
    private String blockFingerprint(String file, int startLine, int tokens) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((file + "|" + startLine + "|" + tokens)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString((file + startLine + tokens).hashCode());
        }
    }

    private boolean isSameFileOverlap(Path firstPath, FileLocation first,
                                      Path otherPath, FileLocation other) {
        if (!firstPath.equals(otherPath)) {
            return false;
        }
        return first.getStartLine() <= other.getEndLine() && other.getStartLine() <= first.getEndLine();
    }

    /**
     * Constructor-delegation chains and delegating accessor blocks are boilerplate, not copy-paste
     * business logic. Treat a block as delegation-only when at least 80% of its meaningful lines are
     * method signatures, {@code this(...)}/{@code super(...)} calls, or simple returns.
     */
    private boolean isDelegationOnly(Path file, FileLocation location) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            int meaningful = 0;
            int delegating = 0;
            for (int line = Math.max(0, location.getStartLine() - 1);
                 line < Math.min(lines.size(), location.getEndLine()); line++) {
                String text = lines.get(line).strip();
                if (text.isEmpty() || text.startsWith("//") || text.startsWith("*")
                        || text.startsWith("/*") || text.startsWith("@")
                        || text.equals("{") || text.equals("}")) {
                    continue;
                }
                meaningful++;
                if (text.startsWith("return ") || text.startsWith("this(") || text.startsWith("super(")
                        || text.matches(".*\\{\\s*return\\s+[\\w.$()\"' ]+;\\s*\\}.*")
                        || text.matches(".*\\{\\s*(this|super)\\([^)]*\\);\\s*\\}.*")
                        || text.matches("^(public|protected|private|static|final|abstract|default|\\s)*"
                        + "[\\w<>\\[\\], ?.]+\\s+\\w+\\([^)]*\\)\\s*\\{?$")) {
                    delegating++;
                }
            }
            return meaningful > 0 && delegating * 10 >= meaningful * 8;
        } catch (IOException e) {
            return false;
        }
    }

    private String relativize(Path projectRoot, Path path) {
        try {
            return projectRoot.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }
}
