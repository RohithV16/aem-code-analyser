package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor;
import io.github.rohithv16.aem.analyser.core.model.report.CoverageReport;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers and ingests test execution coverage from JaCoCo artifacts
 * (jacoco.csv, jacoco.xml, or jacoco.exec) in the target project.
 */
public final class CoverageDiscoveryEngine {

    public CoverageReport discover(Path projectRoot, List<ModuleDescriptor> modules) {
        List<Path> candidateRoots = new ArrayList<>();
        candidateRoots.add(projectRoot);
        for (ModuleDescriptor module : modules) {
            candidateRoots.add(module.moduleDirectory());
        }

        int totalCoveredLines = 0;
        int totalMissedLines = 0;
        int totalCoveredBranches = 0;
        int totalMissedBranches = 0;
        String detectedSource = null;

        for (Path root : candidateRoots) {
            // 1. Try CSV
            Path csvFile = root.resolve("target/site/jacoco/jacoco.csv");
            if (Files.isRegularFile(csvFile)) {
                CoverageReport rep = parseCsv(csvFile, projectRoot);
                if (rep.present()) {
                    totalCoveredLines += rep.coveredLines();
                    totalMissedLines += (rep.totalLines() - rep.coveredLines());
                    totalCoveredBranches += rep.coveredBranches();
                    totalMissedBranches += (rep.totalBranches() - rep.coveredBranches());
                    detectedSource = rep.sourcePath();
                    continue;
                }
            }

            // 2. Try jacoco.exec with classes
            Path execFile = root.resolve("target/jacoco.exec");
            Path classesDir = root.resolve("target/classes");
            if (Files.isRegularFile(execFile) && Files.isDirectory(classesDir)) {
                CoverageReport rep = parseExec(execFile, classesDir, projectRoot);
                if (rep.present()) {
                    totalCoveredLines += rep.coveredLines();
                    totalMissedLines += (rep.totalLines() - rep.coveredLines());
                    totalCoveredBranches += rep.coveredBranches();
                    totalMissedBranches += (rep.totalBranches() - rep.coveredBranches());
                    detectedSource = rep.sourcePath();
                }
            }
        }

        int allLines = totalCoveredLines + totalMissedLines;
        int allBranches = totalCoveredBranches + totalMissedBranches;
        if (allLines == 0) {
            return CoverageReport.notFound();
        }

        double linePct = (totalCoveredLines * 100.0) / allLines;
        double branchPct = allBranches > 0 ? (totalCoveredBranches * 100.0) / allBranches : 0.0;

        return new CoverageReport(
                true,
                detectedSource,
                totalCoveredLines,
                allLines,
                linePct,
                totalCoveredBranches,
                allBranches,
                branchPct
        );
    }

    private CoverageReport parseCsv(Path csvFile, Path projectRoot) {
        int covLine = 0;
        int missLine = 0;
        int covBranch = 0;
        int missBranch = 0;
        try (BufferedReader reader = Files.newBufferedReader(csvFile)) {
            String header = reader.readLine();
            if (header == null) {
                return CoverageReport.notFound();
            }
            String[] cols = header.split(",");
            int lineCovIdx = -1, lineMissIdx = -1, branchCovIdx = -1, branchMissIdx = -1;
            for (int i = 0; i < cols.length; i++) {
                String c = cols[i].trim();
                if ("LINE_COVERED".equalsIgnoreCase(c)) lineCovIdx = i;
                else if ("LINE_MISSED".equalsIgnoreCase(c)) lineMissIdx = i;
                else if ("BRANCH_COVERED".equalsIgnoreCase(c)) branchCovIdx = i;
                else if ("BRANCH_MISSED".equalsIgnoreCase(c)) branchMissIdx = i;
            }
            if (lineCovIdx == -1 || lineMissIdx == -1) {
                return CoverageReport.notFound();
            }

            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length > Math.max(lineCovIdx, lineMissIdx)) {
                    covLine += Integer.parseInt(parts[lineCovIdx].trim());
                    missLine += Integer.parseInt(parts[lineMissIdx].trim());
                    if (branchCovIdx != -1 && parts.length > branchCovIdx) {
                        covBranch += Integer.parseInt(parts[branchCovIdx].trim());
                    }
                    if (branchMissIdx != -1 && parts.length > branchMissIdx) {
                        missBranch += Integer.parseInt(parts[branchMissIdx].trim());
                    }
                }
            }
        } catch (Exception e) {
            return CoverageReport.notFound();
        }

        int totalLines = covLine + missLine;
        int totalBranches = covBranch + missBranch;
        double linePct = totalLines > 0 ? (covLine * 100.0) / totalLines : 0.0;
        double branchPct = totalBranches > 0 ? (covBranch * 100.0) / totalBranches : 0.0;

        return new CoverageReport(
                totalLines > 0,
                relativize(projectRoot, csvFile),
                covLine,
                totalLines,
                linePct,
                covBranch,
                totalBranches,
                branchPct
        );
    }

    private CoverageReport parseExec(Path execFile, Path classesDir, Path projectRoot) {
        try {
            ExecFileLoader loader = new ExecFileLoader();
            loader.load(execFile.toFile());
            CoverageBuilder builder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);

            try (java.util.stream.Stream<Path> stream = Files.walk(classesDir)) {
                stream.filter(f -> f.toString().endsWith(".class")).forEach(classFile -> {
                    try (var in = Files.newInputStream(classFile)) {
                        analyzer.analyzeClass(in, classFile.toString());
                    } catch (Exception ignored) {
                        // Skip single class bytecode mismatch
                    }
                });
            }

            int covLine = 0;
            int totalLines = 0;
            int covBranch = 0;
            int totalBranches = 0;

            for (IClassCoverage cc : builder.getClasses()) {
                covLine += cc.getLineCounter().getCoveredCount();
                totalLines += cc.getLineCounter().getTotalCount();
                covBranch += cc.getBranchCounter().getCoveredCount();
                totalBranches += cc.getBranchCounter().getTotalCount();
            }

            if (totalLines == 0) {
                return CoverageReport.notFound();
            }

            double linePct = (covLine * 100.0) / totalLines;
            double branchPct = totalBranches > 0 ? (covBranch * 100.0) / totalBranches : 0.0;

            return new CoverageReport(
                    true,
                    relativize(projectRoot, execFile),
                    covLine,
                    totalLines,
                    linePct,
                    covBranch,
                    totalBranches,
                    branchPct
            );
        } catch (Exception e) {
            return CoverageReport.notFound();
        }
    }

    private String relativize(Path projectRoot, Path file) {
        try {
            return projectRoot.relativize(file).toString();
        } catch (Exception e) {
            return file.toString();
        }
    }
}
