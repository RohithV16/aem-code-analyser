package io.github.rohithv16.aem.analyser.cli;

import io.github.rohithv16.aem.analyser.core.config.AnalyserConfigLoader;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a failure into one sentence that names the input, says what was expected and says what to
 * change.
 *
 * <p>Raw Jackson and picocli internals never reach the console: "Cannot construct instance of
 * java.util.LinkedHashMap ... at [Source: (BufferedReader); line: 2, column: 8]" names a Java class,
 * not the user's mistake, and the stack trace belongs behind {@code --debug}.</p>
 */
final class FailureMessage {

    /** Prefix {@code DefaultAemCodeAnalyser} adds to every wrapped failure. */
    private static final String FAILED_PREFIX = "Analysis failed: ";

    /** Jackson's reference chain: {@code ...AnalyserConfig["rules"]}. */
    private static final Pattern REFERENCE_FIELD = Pattern.compile("\\[\"([^\"]+)\"\\]");

    /** Jackson's unknown-property message. */
    private static final Pattern UNKNOWN_FIELD = Pattern.compile("Unrecognized field\\s+\"([^\"]+)\"");

    private FailureMessage() {
    }

    /**
     * What is wrong with the path the user pointed at, or {@code null} when it is usable.
     *
     * <p>Only the path itself is checked: a directory inside a module has no {@code pom.xml} and is
     * still a valid starting point, because the reactor is found by walking up.
     */
    static String pathProblem(Path path) {
        if (path == null) {
            return "No project root given: pass --project-root <dir> (or run inside the project).";
        }
        if (!Files.exists(path)) {
            return "Project root does not exist: " + path
                    + ". Check the path given to --project-root (or the working directory).";
        }
        if (!Files.isDirectory(path)) {
            return "Project root is not a directory: " + path
                    + ". Point --project-root at the directory that contains pom.xml.";
        }
        if (!Files.isReadable(path)) {
            return "Project root is not readable: " + path
                    + ". Check the directory permissions for the user running the analyser.";
        }
        return null;
    }

    /** What is wrong with the resolved reactor root, or {@code null} when it holds a POM. */
    static String missingPom(Path root) {
        if (root == null || Files.isRegularFile(root.resolve(AnalyserCli.POM_FILE))) {
            return null;
        }
        return "No pom.xml in " + root + ": this is not a Maven project root."
                + " Point --project-root at the directory that contains the reactor pom.xml.";
    }

    /**
     * One sentence for a configuration that could not be read. The offending field is named: an
     * unknown key and a {@code rules} block with the wrong shape need different fixes.
     */
    static String configuration(Path file, Throwable failure) {
        String name = file == null ? AnalyserConfigLoader.DEFAULT_FILE : file.toString();
        if (failure instanceof NoSuchFileException) {
            return "Configuration file not found: " + name + ". Create it, or drop --config to use "
                    + AnalyserConfigLoader.DEFAULT_FILE + " when present.";
        }
        String field = fieldOf(failure);
        if ("rules".equals(field)) {
            return "Could not read configuration " + name
                    + ": 'rules' must be a mapping of rule id to severity, not a single value."
                    + " Write one rule per line under 'rules:', e.g. '  AEM-DEAD-001: OFF'.";
        }
        if (field != null && !field.isBlank()) {
            return "Could not read configuration " + name + ": unexpected setting '" + field
                    + "'. Allowed top-level keys are schemaVersion, rules and allowlists.";
        }
        return "Could not read configuration " + name + ": " + describeParseProblem(failure)
                + ". Fix " + name + " and rerun.";
    }

    /** Removes the prefix every layer adds, so the console never prints it twice. */
    static String stripFailurePrefixes(String message) {
        if (message == null || message.isBlank()) {
            return "no detail was reported";
        }
        String cleaned = message.strip();
        while (cleaned.startsWith(FAILED_PREFIX)) {
            cleaned = cleaned.substring(FAILED_PREFIX.length()).strip();
        }
        return cleaned;
    }

    /** The first line of an exception's message, without any stack trace or class chain. */
    static String oneLine(Throwable failure) {
        if (failure == null) {
            return "no detail was reported";
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.lines().findFirst().orElse("").strip();
    }

    /** The Jackson field the failure refers to, walking the cause chain; {@code null} when none. */
    private static String fieldOf(Throwable failure) {
        String last = null;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null || message.isBlank()) {
                continue;
            }
            Matcher unknown = UNKNOWN_FIELD.matcher(message);
            if (unknown.find()) {
                return unknown.group(1);
            }
            Matcher reference = REFERENCE_FIELD.matcher(message);
            while (reference.find()) {
                last = reference.group(1);
            }
        }
        return last;
    }

    /** A parse problem in plain words, without Jackson's token/source jargon. */
    private static String describeParseProblem(Throwable failure) {
        String first = oneLine(failure);
        if (first.contains("at [Source") || first.startsWith("Cannot construct instance")
                || first.startsWith("Cannot deserialize value") || first.contains("Unrecognized token")) {
            return "the YAML does not match the configuration schema (check indentation and value types)";
        }
        return first;
    }
}
