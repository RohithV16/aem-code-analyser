package io.github.rohithv16.aem.analyser.cli;

import io.github.rohithv16.aem.analyser.core.api.AnalysisContext;
import io.github.rohithv16.aem.analyser.core.api.AemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.api.AnalysisException;
import io.github.rohithv16.aem.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.rohithv16.aem.analyser.core.export.ConsoleReportFormatter;
import io.github.rohithv16.aem.analyser.core.export.ExportException;
import io.github.rohithv16.aem.analyser.core.export.ReportFormat;
import io.github.rohithv16.aem.analyser.core.export.SarifReportExporter;
import io.github.rohithv16.aem.analyser.core.export.SonarReportExporter;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
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

    @Option(names = {"--collect-coverage"},
            description = "Automatically execute unit tests with dynamic JaCoCo instrumentation to generate fresh coverage reports (zero POM configuration required).")
    private boolean collectCoverage;

    @Option(names = {"--force"},
            description = "Force re-execution of unit tests when using --collect-coverage even if coverage artifacts already exist.")
    private boolean force;

    private final PrintWriter stdout = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

    @Override
    public void run() {
        try {
            Path root = resolveProjectRoot();
            if (collectCoverage) {
                runCoverageCollection(root);
            }
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

    private void runCoverageCollection(Path root) {
        Path existing = findExistingCoverage(root);
        if (existing != null && !force) {
            String rel = root.relativize(existing).toString();
            stdout.println("[INFO] Existing JaCoCo coverage found (" + rel + "); skipping test execution. Use --force to re-run.");
            return;
        }

        stdout.println("[INFO] Dynamic JaCoCo enabled: executing unit tests to collect test code coverage...");
        List<String> cmd = resolveMavenCommand(root);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(root.toFile());
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                stdout.println("[WARNING] Maven test execution returned code " + exitCode + "; continuing analysis with available artifacts.");
            } else {
                stdout.println("[INFO] Test coverage collected successfully.");
            }
        } catch (IOException e) {
            stdout.println("[WARNING] Unable to run Maven to collect coverage: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stdout.println("[WARNING] Coverage collection interrupted.");
        }
    }

    private List<String> resolveMavenCommand(Path root) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path mvnw = root.resolve(isWindows ? "mvnw.cmd" : "mvnw");
        String cmd;
        if (Files.isExecutable(mvnw)) {
            cmd = mvnw.toAbsolutePath().toString();
        } else {
            cmd = isWindows ? "mvn.cmd" : "mvn";
        }
        List<String> command = new ArrayList<>();
        command.add(cmd);
        Path pomPath = root.resolve("pom.xml");
        if (Files.isRegularFile(pomPath)) {
            command.add("-f");
            command.add(pomPath.toAbsolutePath().toString());
        }

        boolean hasJacoco = projectHasJacoco(root);
        if (hasJacoco) {
            command.add("test");
        } else {
            command.add("org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent");
            command.add("test");
            command.add("org.jacoco:jacoco-maven-plugin:0.8.12:report");
        }

        // Only build and test the Java modules that actually contain unit tests
        List<String> testModules = findJavaTestModules(root);
        if (!testModules.isEmpty()) {
            command.add("-pl");
            command.add(String.join(",", testModules));
        }

        command.add("-Dmaven.test.failure.ignore=true");
        command.add("-Dnpm.skip=true");
        command.add("-Dfrontend.skip=true");
        command.add("-Dskip.npm=true");
        command.add("-Dcheckstyle.skip=true");
        command.add("-Dspotless.check.skip=true");

        if (profiles != null && !profiles.isEmpty()) {
            command.add("-P" + String.join(",", profiles));
        }
        return command;
    }

    Path findExistingCoverage(Path root) {
        try (var stream = Files.walk(root, 4)) {
            return stream.filter(p -> {
                String s = p.toString();
                return (s.endsWith("target/jacoco.exec") || s.endsWith("target/site/jacoco/jacoco.csv"))
                        && Files.isRegularFile(p);
            }).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    boolean projectHasJacoco(Path root) {
        try (var stream = Files.walk(root, 4)) {
            return stream.filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .anyMatch(pom -> {
                        try {
                            String content = Files.readString(pom);
                            return content.contains("jacoco-maven-plugin");
                        } catch (IOException e) {
                            return false;
                        }
                    });
        } catch (IOException e) {
            return false;
        }
    }

    List<String> findJavaTestModules(Path root) {
        List<String> modules = new ArrayList<>();
        try (var stream = Files.walk(root, 3)) {
            stream.filter(p -> p.getFileName().toString().equals("pom.xml") && !p.equals(root.resolve("pom.xml")))
                    .forEach(pom -> {
                        Path moduleDir = pom.getParent();
                        if (Files.isDirectory(moduleDir.resolve("src/test/java"))) {
                            try {
                                String rel = root.relativize(moduleDir).toString();
                                if (!rel.isEmpty() && !modules.contains(rel)) {
                                    modules.add(rel);
                                }
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    });
        } catch (IOException ignored) {
        }
        return modules;
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
