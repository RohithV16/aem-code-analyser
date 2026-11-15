package io.github.rohithv16.aem.analyser.core.sonar;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.sonar.api.batch.fs.InputComponent;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.check.Rule;
import org.sonar.java.SonarComponents;
import org.sonar.java.checks.verifier.internal.InternalSensorContext;
import org.sonar.java.classpath.ClasspathForMain;
import org.sonar.java.classpath.ClasspathForTest;
import org.sonar.java.reporting.AnalyzerMessage;
import org.sonar.plugins.java.api.JavaCheck;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone harness extending SonarSource's SonarComponents to intercept
 * issues reported by JavaCheck visitors without requiring an active SonarQube server.
 */
public class SonarBridgeComponents extends SonarComponents {

    private final Path projectRoot;
    private final List<Violation> violations = new ArrayList<>();

    public SonarBridgeComponents(Path projectRoot, InternalSensorContext sensorContext) {
        super(null, sensorContext.fileSystem(),
                new ClasspathForMain(sensorContext.config(), sensorContext.fileSystem()),
                new ClasspathForTest(sensorContext.config(), sensorContext.fileSystem()),
                null, null);
        this.projectRoot = projectRoot;
        setSensorContext(sensorContext);
    }

    @Override
    public boolean isQuickFixCompatible() {
        return false;
    }

    @Override
    public boolean fileCanBeSkipped(InputFile file) {
        return false;
    }

    @Override
    public boolean canSkipUnchangedFiles() {
        return false;
    }

    @Override
    public void reportIssue(AnalyzerMessage message) {
        if (message == null || message.getCheck() == null) {
            return;
        }
        JavaCheck check = message.getCheck();
        Rule rule = check.getClass().getAnnotation(Rule.class);
        String ruleKey = (rule != null && rule.key() != null) ? rule.key() : check.getClass().getSimpleName();
        String ruleId = "java:" + ruleKey;

        AnalyzerMessage.TextSpan span = message.primaryLocation();
        TextRegion region = (span != null && span.startLine > 0)
                ? new TextRegion(span.startLine, span.startCharacter, span.endLine, span.endCharacter)
                : (message.getLine() != null ? TextRegion.forLine(message.getLine()) : TextRegion.forWholeFile());

        InputComponent comp = message.getInputComponent();
        String pathStr = "";
        if (comp instanceof InputFile ifile) {
            pathStr = ifile.path() != null ? ifile.path().toString().replace('\\', '/') : ifile.filename();
            if (projectRoot != null && ifile.path() != null) {
                try {
                    pathStr = projectRoot.toAbsolutePath().normalize().relativize(ifile.path().toAbsolutePath().normalize())
                            .toString().replace('\\', '/');
                } catch (Exception ignored) {
                }
            }
        }

        Severity severity = determineSeverity(ruleKey);

        violations.add(new Violation(
                ruleId,
                severity,
                ruleKey,
                message.getMessage(),
                pathStr,
                region,
                null,
                "Follow SonarJava rule " + ruleKey + " (" + check.getClass().getSimpleName() + ") standards.",
                List.of()
        ));
    }

    @Override
    public void addIssue(InputComponent component, JavaCheck check, int line, String message, Integer cost) {
        if (check == null) {
            return;
        }
        Rule rule = check.getClass().getAnnotation(Rule.class);
        String ruleKey = (rule != null && rule.key() != null) ? rule.key() : check.getClass().getSimpleName();
        String ruleId = "java:" + ruleKey;

        String pathStr = "";
        if (component instanceof InputFile ifile) {
            pathStr = ifile.path() != null ? ifile.path().toString().replace('\\', '/') : ifile.filename();
            if (projectRoot != null && ifile.path() != null) {
                try {
                    pathStr = projectRoot.toAbsolutePath().normalize().relativize(ifile.path().toAbsolutePath().normalize())
                            .toString().replace('\\', '/');
                } catch (Exception ignored) {
                }
            }
        }

        Severity severity = determineSeverity(ruleKey);

        violations.add(new Violation(
                ruleId,
                severity,
                ruleKey,
                message,
                pathStr,
                line > 0 ? TextRegion.forLine(line) : TextRegion.forWholeFile(),
                null,
                "Follow SonarJava rule " + ruleKey + " (" + check.getClass().getSimpleName() + ") standards.",
                List.of()
        ));
    }

    private Severity determineSeverity(String ruleKey) {
        if (ruleKey.startsWith("S2168") || ruleKey.startsWith("S2142") || ruleKey.startsWith("S4423")
                || ruleKey.startsWith("S4790") || ruleKey.startsWith("S2077") || ruleKey.startsWith("S1147")) {
            return Severity.ERROR;
        }
        if (ruleKey.startsWith("S1135") || ruleKey.startsWith("S1161") || ruleKey.startsWith("S2204")) {
            return Severity.INFO;
        }
        return Severity.WARNING;
    }

    public List<Violation> getViolations() {
        return violations;
    }
}
