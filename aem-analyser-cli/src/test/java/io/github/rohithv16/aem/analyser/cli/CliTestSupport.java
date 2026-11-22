package io.github.rohithv16.aem.analyser.cli;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Helpers shared by the CLI contract tests: running the command in-process with captured stdout and
 * building throw-away projects and reports.
 */
final class CliTestSupport {

    private CliTestSupport() {
    }

    /** The exit code and captured standard output of one in-process CLI invocation. */
    record Run(int exitCode, String output) {
    }

    /**
     * Runs the CLI against {@code project} and captures its console report.
     *
     * <p>The command binds its writer in the constructor, so the stream redirection has to be in
     * place before the command object is created.
     */
    static Run run(Path project, String... options) throws Exception {
        String[] args = new String[options.length + 1];
        args[0] = project.toString();
        System.arraycopy(options, 0, args, 1, options.length);

        return runArgs(args);
    }

    static Run runArgs(String... args) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            int exit = new CommandLine(new AnalyserCli()).execute(args);
            return new Run(exit, captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
        }
    }

    /** A project with only a POM, so a full analysis finds nothing and stays fast. */
    static void writeMinimalProject(Path project) throws java.io.IOException {
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><artifactId>fixture</artifactId></project>");
    }

    static AnalysisReport reportWith(Violation... violations) {
        return new AnalysisReport(Path.of("/tmp/project"), Map.of(), List.of(violations), 1);
    }

    static Violation errorViolation() {
        return new Violation("AEM-DEAD-001", Severity.ERROR, null, "Orphaned config",
                "ui.config/config.cfg.json", new TextRegion(1, 1, 1, 10), null, null, List.of());
    }

    static Violation warningViolation() {
        return new Violation("AEM-DUP-010", Severity.WARNING, null, "Duplicate code",
                "core/Service.java", new TextRegion(20, 1, 20, 10), null, null, List.of());
    }

    static Violation infoViolation() {
        return new Violation("AEM-DEAD-003B", Severity.INFO, null, "Unused model",
                "core/Model.java", new TextRegion(10, 1, 10, 10), null, null, List.of());
    }
}
