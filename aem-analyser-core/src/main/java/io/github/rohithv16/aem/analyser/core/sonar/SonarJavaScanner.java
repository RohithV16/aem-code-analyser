package io.github.rohithv16.aem.analyser.core.sonar;

import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.java.ast.JavaAstScanner;
import org.sonar.java.checks.verifier.internal.InternalInputFile;
import org.sonar.java.checks.verifier.internal.InternalSensorContext;
import org.sonar.java.model.JavaVersionImpl;
import org.sonar.java.model.VisitorsBridge;
import org.sonar.plugins.java.api.JavaFileScanner;
import org.sonar.plugins.java.api.JavaVersion;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Standalone runner for SonarSource Java checks over a set of Java source files.
 *
 * <p>The scanner no longer discovers anything. The engine hands it the run's already-discovered
 * Java files (the same set the AEM rules parse) and the reactor's classpath directories, so
 * enabling Sonar cannot widen {@code --changed-only} or {@code --max-file-size} behind the user's
 * back. Removing the two root walks this phase used to perform is what makes it cheap: the AST pass
 * itself is unchanged.</p>
 *
 * <p>Why one harness and not a worker pool: SonarJava 8.1's AST scanner keeps process-global
 * parser/symbol state, and running harnesses concurrently silently loses findings (measured on the
 * validation portfolio: 30-31 findings at 2+ threads versus 38 sequentially, for the same file
 * list). Correctness wins, so the pass is single-threaded; the filters and the removed walks are
 * the measured win.</p>
 */
public final class SonarJavaScanner {

    private final Path projectRoot;
    private final List<Path> javaFiles;
    private final List<File> classpath;
    private final List<JavaFileScanner> checks;

    public SonarJavaScanner(Path projectRoot) {
        this(projectRoot, SonarRuleRegistry.createDefaultChecks());
    }

    /** Legacy constructor: discovers the file set and classpath by walking the root. */
    public SonarJavaScanner(Path projectRoot, List<JavaFileScanner> checks) {
        this(projectRoot, null, null, checks);
    }

    /**
     * @param javaFiles the exact files to analyse (already filtered by changed-only and size), or
     *                  {@code null} to fall back to a walk of {@code projectRoot}
     * @param classpath the reactor's {@code target/classes} directories, or {@code null} to
     *                  discover them by walking
     */
    public SonarJavaScanner(Path projectRoot, List<Path> javaFiles, List<File> classpath,
                            List<JavaFileScanner> checks) {
        this.projectRoot = projectRoot;
        this.javaFiles = javaFiles;
        this.classpath = classpath;
        this.checks = checks != null ? checks : SonarRuleRegistry.createDefaultChecks();
    }

    public List<Violation> scan() {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return List.of();
        }

        List<Path> paths = javaFiles != null ? javaFiles : discoverJavaSourceFiles(projectRoot);
        if (paths.isEmpty()) {
            return List.of();
        }
        List<File> effectiveClasspath = classpath != null ? classpath : discoverClasspaths(projectRoot);

        InternalSensorContext sensorContext = new InternalSensorContext();
        SonarBridgeComponents bridgeComponents = new SonarBridgeComponents(projectRoot, sensorContext);
        JavaVersion javaVersion = JavaVersionImpl.fromString("21");

        VisitorsBridge visitorsBridge = new VisitorsBridge(checks, effectiveClasspath, bridgeComponents, javaVersion);
        JavaAstScanner astScanner = new JavaAstScanner(bridgeComponents);
        astScanner.setVisitorBridge(visitorsBridge);

        List<InputFile> inputFiles = new ArrayList<>(paths.size());
        for (Path path : paths) {
            try {
                inputFiles.add(InternalInputFile.inputFile("project", path.toFile()));
            } catch (Exception ignored) {
                // An unreadable/unsupported file is skipped: the parse phase already reports it.
            }
        }
        try {
            astScanner.scan(inputFiles);
        } catch (Throwable ignored) {
            // Partial results from the checks that did run are still reported.
        }
        return bridgeComponents.getViolations();
    }

    private List<Path> discoverJavaSourceFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return !s.contains("/target/") && !s.contains("/node_modules/")
                                && !s.contains("/.git/") && !s.contains("/src/test/");
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private List<File> discoverClasspaths(Path root) {
        List<File> cp = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equals("classes") && p.getParent() != null
                            && p.getParent().getFileName().toString().equals("target"))
                    .forEach(p -> cp.add(p.toFile()));
        } catch (IOException ignored) {
        }
        return cp;
    }
}
