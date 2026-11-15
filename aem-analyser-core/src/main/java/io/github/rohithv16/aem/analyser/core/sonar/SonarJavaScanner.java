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
 * Standalone runner for SonarSource Java checks over a project directory tree.
 */
public final class SonarJavaScanner {

    private final Path projectRoot;
    private final List<JavaFileScanner> checks;

    public SonarJavaScanner(Path projectRoot) {
        this(projectRoot, SonarRuleRegistry.createDefaultChecks());
    }

    public SonarJavaScanner(Path projectRoot, List<JavaFileScanner> checks) {
        this.projectRoot = projectRoot;
        this.checks = checks != null ? checks : SonarRuleRegistry.createDefaultChecks();
    }

    public List<Violation> scan() {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return List.of();
        }

        // 1. Discover all .java source files
        List<Path> javaPaths = discoverJavaSourceFiles(projectRoot);
        if (javaPaths.isEmpty()) {
            return List.of();
        }

        // 2. Discover compiled classpath directories
        List<File> classpath = discoverClasspaths(projectRoot);

        // 3. Initialize Sonar harness & InputFiles
        InternalSensorContext sensorContext = new InternalSensorContext();
        SonarBridgeComponents bridgeComponents = new SonarBridgeComponents(projectRoot, sensorContext);
        JavaVersion javaVersion = JavaVersionImpl.fromString("21");

        VisitorsBridge visitorsBridge = new VisitorsBridge(checks, classpath, bridgeComponents, javaVersion);
        JavaAstScanner astScanner = new JavaAstScanner(bridgeComponents);
        astScanner.setVisitorBridge(visitorsBridge);

        List<InputFile> inputFiles = new ArrayList<>();
        for (Path p : javaPaths) {
            try {
                inputFiles.add(InternalInputFile.inputFile("project", p.toFile()));
            } catch (Exception ignored) {
            }
        }

        // 4. Run AST scanner
        try {
            astScanner.scan(inputFiles);
        } catch (Throwable ignored) {
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
