package io.github.aemtools.analyser.cli;

import io.github.aemtools.analyser.core.api.AnalysisContext;
import io.github.aemtools.analyser.core.api.AemCodeAnalyser;
import io.github.aemtools.analyser.core.api.AnalysisException;
import io.github.aemtools.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.aemtools.analyser.core.export.ConsoleReportFormatter;
import io.github.aemtools.analyser.core.export.ExportException;
import io.github.aemtools.analyser.core.export.ReportFormat;
import io.github.aemtools.analyser.core.export.SarifReportExporter;
import io.github.aemtools.analyser.core.export.SonarReportExporter;
import io.github.aemtools.analyser.core.model.report.AnalysisReport;
import io.github.aemtools.analyser.core.model.report.Severity;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * FEAT-DELIV-02: standalone Picocli command-line executable.
 * Designed for sub-second developer inner-loop scans, pre-commit hooks and
 * standalone audits of unbuilt repositories.
 */
@Command(name = "aem-analyser",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "AEM Code Analyser — cross-boundary dead code detection engine "
                + "(Phase 1: Java & OSGi configurations).")
public class AnalyserCli implements Runnable {

    @Option(names = {"-p", "--project-root"},
            description = "Project (reactor) root directory. Defaults to the current directory, "
                    + "auto-discovering the enclosing repository root.")
    private Path projectRoot;

    @Option(names = {"-f", "--format"}, split = ",",
            description = "Output formats (comma-separated): CONSOLE, SARIF, SONAR. Default: CONSOLE.")
    private List<String> formats = List.of("CONSOLE");

    @Option(names = {"-o", "--output"},
            description = "Output directory for SARIF/SONAR files. Default: ./target/aem-analyser")
    private Path outputDirectory;

    @Option(names = {"-t", "--threads"},
            description = "Worker thread count for parallel parsing. Default: available processors - 1.")
    private Integer threads;

    @Option(names = {"--profile"}, split = ",",
            description = "Maven profiles to activate during module discovery.")
    private List<String> profiles;

    @Option(names = {"-w", "--fail-on-warning"},
            description = "Exit with a non-zero code when warnings are detected.")
    private boolean failOnWarning;

    @Option(names = {"--no-color"}, description = "Disable ANSI colors in console output.")
    private boolean noColor;

    @Option(names = {"--ignored-services"}, split = ",",
            description = "Service implementation class names to whitelist (never flagged unused).")
    private Set<String> ignoredServices;

    private final PrintWriter stdout = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

    @Override
    public void run() {
        try {
            Path root = resolveProjectRoot();
            AnalysisContext.Builder builder = AnalysisContext.builder(root);
            if (profiles != null && !profiles.isEmpty()) {
                builder.activeProfiles(profiles);
            }
            if (threads != null && threads > 0) {
                builder.threads(threads);
            }
            if (ignoredServices != null && !ignoredServices.isEmpty()) {
                builder.ignoredServices(ignoredServices);
            }

            AnalysisReport report = new DefaultAemCodeAnalyser().analyze(builder.build());
            export(report);
            System.exit(exitCode(report));
        } catch (AnalysisException e) {
            stdout.println("[ERROR] Analysis failed: " + e.getMessage());
            System.exit(2);
        } catch (IOException | ExportException e) {
            stdout.println("[ERROR] Report export failed: " + e.getMessage());
            System.exit(2);
        }
    }

    /**
     * Auto-discovers the enclosing Maven reactor root when invoked from a subfolder.
     */
    Path resolveProjectRoot() {
        Path root = projectRoot != null ? projectRoot.toAbsolutePath().normalize()
                : Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return root;
        }
        Path parent = root;
        for (int i = 0; i < 6 && parent.getParent() != null; i++) {
            parent = parent.getParent();
            if (Files.isRegularFile(parent.resolve("pom.xml"))) {
                return parent;
            }
        }
        return root;
    }

    private void export(AnalysisReport report) throws IOException, ExportException {
        for (String format : formats) {
            ReportFormat reportFormat = ReportFormat.valueOf(format.strip().toUpperCase(Locale.ROOT));
            switch (reportFormat) {
                case CONSOLE -> new ConsoleReportFormatter(!noColor).export(report, stdout);
                case SARIF -> {
                    Path target = outputTarget("aem-analyser.sarif");
                    try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                        new SarifReportExporter().export(report, writer);
                    }
                    stdout.println("SARIF report written to " + target);
                }
                case SONAR -> {
                    Path target = outputTarget("sonar-aem-analyser-report.json");
                    try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                        new SonarReportExporter().export(report, writer);
                    }
                    stdout.println("SonarQube report written to " + target);
                }
            }
        }
    }

    private Path outputTarget(String fileName) throws IOException {
        Path target = outputDirectory != null ? outputDirectory
                : Path.of("target/aem-analyser");
        Path resolved = Files.createDirectories(target.toAbsolutePath().normalize());
        return resolved.resolve(fileName);
    }

    int exitCode(AnalysisReport report) {
        long errors = report.countBySeverity(Severity.ERROR);
        long warnings = report.countBySeverity(Severity.WARNING);
        if (errors > 0) {
            return 1;
        }
        if (failOnWarning && warnings > 0) {
            return 1;
        }
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AnalyserCli()).execute(args);
        System.exit(exitCode);
    }
}
