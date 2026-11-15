package io.github.rohithv16.aem.analyser.core.sonar;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SonarAemSuppressionsTest {

    @TempDir
    Path root;

    private Violation violation(String ruleId, String file, int line) {
        return new Violation(ruleId, Severity.WARNING, "SONAR", "message", file,
                new TextRegion(line, 5, line, 20), null, null, List.of());
    }

    @Test
    void suppressesWebConsoleFactoryNameHint() throws Exception {
        Files.writeString(root.resolve("Config.java"), """
                @interface Config {
                    String webconsole_configurationFactory_nameHint() default "";
                }
                """);

        assertTrue(SonarAemSuppressions.isSuppressed(root,
                violation("java:S100", "Config.java", 2)));
    }

    @Test
    void suppressesEmptyServletFilterLifecycleMethods() throws Exception {
        Files.writeString(root.resolve("LoggingFilter.java"), """
                class LoggingFilter implements Filter {
                    public void init(FilterConfig config) { }
                    public void destroy() { }
                }
                """);

        assertTrue(SonarAemSuppressions.isSuppressed(root,
                violation("java:S1186", "LoggingFilter.java", 2)));
        assertTrue(SonarAemSuppressions.isSuppressed(root,
                violation("java:S1186", "LoggingFilter.java", 3)));
    }

    @Test
    void suppressesJcrNodeNameDuplication() throws Exception {
        Files.writeString(root.resolve("Cursor.java"), """
                class Cursor {
                    void write(Node parent) throws Exception {
                        parent.addNode("cursor", "nt:unstructured");
                    }
                }
                """);

        assertTrue(SonarAemSuppressions.isSuppressed(root,
                violation("java:S1192", "Cursor.java", 3)));
    }

    @Test
    void doesNotSuppressAnUnrelatedEmptyMethodOrLiteral() throws Exception {
        Files.writeString(root.resolve("Service.java"), """
                class Service {
                    public void unused() { }
                    void read(String value) { System.out.println("duplicate"); }
                }
                """);

        assertFalse(SonarAemSuppressions.isSuppressed(root,
                violation("java:S1186", "Service.java", 2)));
        assertFalse(SonarAemSuppressions.isSuppressed(root,
                violation("java:S1192", "Service.java", 3)));
    }
}
