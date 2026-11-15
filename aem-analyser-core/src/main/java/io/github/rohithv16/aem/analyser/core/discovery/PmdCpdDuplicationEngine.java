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
import java.nio.file.Files;
import java.nio.file.Path;
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

                duplicateBlocks++;
                duplicatedLines += lineCount;
                fileDuplicatedLines.merge(otherPath, lineCount, Integer::sum);

                String relFirst = relativize(projectRoot, firstPath);
                String relOther = relativize(projectRoot, otherPath);

                List<RelatedLocation> related = List.of(new RelatedLocation(
                        String.format("Original block in %s (%d lines, %d tokens)", firstClassName, lineCount, tokenCount),
                        relFirst,
                        new TextRegion(firstLoc.getStartLine(), 1, firstLoc.getEndLine(), 1)
                ));

                violations.add(new Violation(
                        RuleId.AEM_DUP_010.id(),
                        RuleId.AEM_DUP_010.defaultLevel(),
                        "DUPLICATE_CODE_BLOCK",
                        String.format("Duplicate code block (%d lines, %d tokens) in %s matches %s (lines %d-%d).",
                                lineCount, tokenCount, otherClassName, firstClassName,
                                firstLoc.getStartLine(), firstLoc.getEndLine()),
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
                duplicateClasses
        );

        return new Result(metrics, violations);
    }

    private String relativize(Path projectRoot, Path path) {
        try {
            return projectRoot.relativize(path).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }
}
