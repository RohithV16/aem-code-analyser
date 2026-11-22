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

    /** The exit code and the two captured streams of one in-process CLI invocation. */
    record Run(int exitCode, String output, String errors) {
    }

    /**
     * Runs the CLI against {@code project} and captures its console report.
     *
     * <p>The command binds its writers in the constructor, so the stream redirection has to be in
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
        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(capturedOut, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(capturedErr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            int exit = new CommandLine(new AnalyserCli()).execute(args);
            return new Run(exit, capturedOut.toString(StandardCharsets.UTF_8),
                    capturedErr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /** A project with only a POM, so a full analysis finds nothing and stays fast. */
    static void writeMinimalProject(Path project) throws java.io.IOException {
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><artifactId>fixture</artifactId></project>");
    }

    /**
     * A two-module AEM-shaped project whose only finding is one AEM-QUAL-003 warning, so a test can
     * assert on severity overrides without depending on the analyser's discovery heuristics.
     */
    static void writeSchedulerProject(Path project) throws java.io.IOException {
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId>"
                        + "<artifactId>fixture</artifactId><version>1.0.0</version>"
                        + "<packaging>pom</packaging><modules><module>core</module></modules></project>");
        Path core = Files.createDirectories(project.resolve("core"));
        Files.writeString(core.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<parent><groupId>com.acme</groupId><artifactId>fixture</artifactId>"
                        + "<version>1.0.0</version></parent>"
                        + "<artifactId>fixture.core</artifactId><packaging>bundle</packaging></project>");
        Path sources = Files.createDirectories(core.resolve("src/main/java/com/acme"));
        Files.writeString(sources.resolve("Poller.java"),
                "package com.acme;\n"
                        + "import org.apache.sling.commons.scheduler.Scheduler;\n"
                        + "import org.osgi.service.component.annotations.Component;\n"
                        + "import org.osgi.service.component.annotations.Reference;\n"
                        + "@Component(service = Runnable.class)\n"
                        + "public class Poller implements Runnable {\n"
                        + "    @Reference\n"
                        + "    private Scheduler scheduler;\n"
                        + "    @Override\n"
                        + "    public void run() {\n"
                        + "    }\n"
                        + "}\n");
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
