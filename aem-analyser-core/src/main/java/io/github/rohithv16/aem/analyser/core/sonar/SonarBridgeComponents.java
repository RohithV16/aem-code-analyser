package io.github.rohithv16.aem.analyser.core.sonar;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.sonar.api.batch.fs.InputComponent;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.rule.RuleKey;
import org.sonar.check.Rule;
import org.sonar.java.SonarComponents;
import org.sonar.java.checks.verifier.internal.InternalSensorContext;
import org.sonar.java.classpath.ClasspathForMain;
import org.sonar.java.classpath.ClasspathForTest;
import org.sonar.java.reporting.AnalyzerMessage;
import org.sonar.plugins.java.api.JavaCheck;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Standalone harness extending SonarSource's SonarComponents to intercept
 * issues reported by JavaCheck visitors without requiring an active SonarQube server.
 */
public class SonarBridgeComponents extends SonarComponents {

    /** Repository prefix SonarJava uses in its stable rule ids. */
    private static final String SONAR_RULE_PREFIX = "java:";

    private final Path projectRoot;
    private final List<Violation> violations = new ArrayList<>();
    private final java.util.Set<String> reportedSignatures = new java.util.HashSet<>();

    public SonarBridgeComponents(Path projectRoot, InternalSensorContext sensorContext) {
        super(null, sensorContext.fileSystem(),
                new ClasspathForMain(sensorContext.config(), sensorContext.fileSystem()),
                new ClasspathForTest(sensorContext.config(), sensorContext.fileSystem()),
                null, null);
        this.projectRoot = projectRoot;
        setSensorContext(createBridgedSensorContext(sensorContext));
    }

    private SensorContext createBridgedSensorContext(InternalSensorContext sensorContext) {
        return (SensorContext) Proxy.newProxyInstance(
                SensorContext.class.getClassLoader(),
                new Class<?>[]{SensorContext.class},
                (proxy, method, args) -> {
                    if ("newIssue".equals(method.getName())) {
                        return createNewIssueProxy();
                    }
                    try {
                        return method.invoke(sensorContext, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
        );
    }

    private NewIssue createNewIssueProxy() {
        return (NewIssue) Proxy.newProxyInstance(
                NewIssue.class.getClassLoader(),
                new Class<?>[]{NewIssue.class},
                new NewIssueHandler());
    }

    /**
     * Buffers the {@link NewIssue} builder chain SonarJava drives ({@code forRule} →
     * {@code newLocation().on().at().message()} → {@code save()}) and turns the finished issue into
     * a {@link Violation}, because no SonarQube server is present to receive it.
     */
    private final class NewIssueHandler implements InvocationHandler {

        /** Builder calls that only have to keep the chain alive for the later {@code save()}. */
        private static final Set<String> CHAIN_CALLS = Set.of(
                "addQuickFix", "addLocation", "addFlow", "gap", "overrideSeverity", "overrideImpact",
                "setQuickFixAvailable", "setRuleDescriptionContextKey", "setCodeVariants", "at");

        private RuleKey ruleKey;
        private InputComponent inputComponent;
        private org.sonar.api.batch.fs.TextRange textRange;
        private String message;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("forRule".equals(name) && args != null && args.length > 0) {
                ruleKey = (RuleKey) args[0];
                return proxy;
            }
            if ("newLocation".equals(name)) {
                return newLocationProxy();
            }
            if ("newQuickFix".equals(name)) {
                return createDummyProxy(org.sonar.api.batch.sensor.issue.fix.NewQuickFix.class);
            }
            if (CHAIN_CALLS.contains(name)) {
                return proxy;
            }
            if ("save".equals(name)) {
                saveIssue();
                return null;
            }
            return defaultValue(method.getReturnType(), proxy);
        }

        /** Emits the buffered issue; a builder that never received {@code forRule} saves nothing. */
        private void saveIssue() {
            if (ruleKey == null) {
                return;
            }
            String key = ruleKey.rule();
            org.sonar.api.batch.fs.TextRange range = textRange;
            int startLine = range != null && range.start() != null ? range.start().line() : 1;
            int startColumn = range != null && range.start() != null ? range.start().lineOffset() : 0;
            int endLine = range != null && range.end() != null ? range.end().line() : startLine;
            int endColumn = range != null && range.end() != null ? range.end().lineOffset() : 0;
            TextRegion region = startLine > 0
                    ? new TextRegion(startLine, startColumn, endLine, endColumn)
                    : TextRegion.forWholeFile();
            recordViolation(key, message != null ? message : "Rule violation " + key,
                    projectRelativePath(inputComponent), region, startLine,
                    "Follow SonarJava rule " + key + " standards.");
        }

        private NewIssueLocation newLocationProxy() {
            return (NewIssueLocation) Proxy.newProxyInstance(
                    NewIssueLocation.class.getClassLoader(),
                    new Class<?>[]{NewIssueLocation.class},
                    this::onLocationCall);
        }

        /** Applies {@code on()/at()/message()} to the issue being built. */
        private Object onLocationCall(Object locProxy, Method locMethod, Object[] locArgs) {
            String name = locMethod.getName();
            if (locArgs != null && locArgs.length > 0) {
                if ("on".equals(name)) {
                    inputComponent = (InputComponent) locArgs[0];
                    return locProxy;
                }
                if ("at".equals(name)) {
                    textRange = (org.sonar.api.batch.fs.TextRange) locArgs[0];
                    return locProxy;
                }
                if ("message".equals(name)) {
                    message = String.valueOf(locArgs[0]);
                    return locProxy;
                }
            }
            return defaultValue(locMethod.getReturnType(), locProxy);
        }

        /** Proxy return value for a method this bridge does not implement. */
        private Object defaultValue(Class<?> returnType, Object proxy) {
            if (returnType.isAssignableFrom(proxy.getClass())) {
                return proxy;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == double.class) {
                return 0.0;
            }
            return null;
        }

        /** A proxy that keeps the builder chain working without recording anything. */
        @SuppressWarnings("unchecked")
        private <T> T createDummyProxy(Class<T> clazz) {
            return (T) Proxy.newProxyInstance(
                    clazz.getClassLoader(),
                    new Class<?>[]{clazz},
                    (dummyProxy, dummyMethod, dummyArgs) ->
                            defaultValue(dummyMethod.getReturnType(), dummyProxy));
        }
    }

    @Override
    public Optional<RuleKey> getRuleKey(JavaCheck check) {
        if (check == null) {
            return Optional.empty();
        }
        return Optional.of(RuleKey.of("java", ruleKeyOf(check)));
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
        String ruleKey = ruleKeyOf(message.getCheck());
        TextRegion region = primaryRegion(message);
        recordViolation(ruleKey, message.getMessage(),
                projectRelativePath(message.getInputComponent()), region, region.startLine(),
                "Follow SonarJava best practice for rule " + ruleKey + ".");
    }

    /** Prefers the analyzer's character-accurate span, then the line, then the whole file. */
    private static TextRegion primaryRegion(AnalyzerMessage message) {
        AnalyzerMessage.TextSpan span = message.primaryLocation();
        if (span != null && span.startLine > 0) {
            return new TextRegion(span.startLine, span.startCharacter, span.endLine, span.endCharacter);
        }
        if (message.getLine() != null) {
            return TextRegion.forLine(message.getLine());
        }
        return TextRegion.forWholeFile();
    }

    @Override
    public void addIssue(InputComponent component, JavaCheck check, int line, String message, Integer cost) {
        if (check == null) {
            return;
        }
        String ruleKey = ruleKeyOf(check);
        TextRegion region = line > 0 ? TextRegion.forLine(line) : TextRegion.forWholeFile();
        recordViolation(ruleKey, message, projectRelativePath(component), region, line,
                "Follow SonarJava best practice for rule " + ruleKey + ".");
    }

    /** The Sonar rule key from the check's {@code @Rule} annotation, else its simple class name. */
    private static String ruleKeyOf(JavaCheck check) {
        Rule rule = check.getClass().getAnnotation(Rule.class);
        return rule != null && rule.key() != null ? rule.key() : check.getClass().getSimpleName();
    }

    /** Project-relative path with forward slashes; absolute when the file sits outside the root. */
    private String projectRelativePath(InputComponent component) {
        if (!(component instanceof InputFile inputFile)) {
            return "";
        }
        if (inputFile.path() == null) {
            return inputFile.filename();
        }
        String absolutePath = inputFile.path().toString().replace('\\', '/');
        if (projectRoot == null) {
            return absolutePath;
        }
        try {
            return projectRoot.toAbsolutePath().normalize()
                    .relativize(inputFile.path().toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (Exception notRelativizable) {
            // A file outside the analysed root keeps its absolute path: the finding is still
            // reported, only not project-relative.
            return absolutePath;
        }
    }

    /**
     * Adds one finding unless the same rule already fired at the same file and line, so a rule that
     * reports twice for one location does not double-count.
     */
    private void recordViolation(String ruleKey, String message, String pathStr, TextRegion region,
                                 int signatureLine, String remediation) {
        String ruleId = SONAR_RULE_PREFIX + ruleKey;
        if (!reportedSignatures.add(ruleId + ":" + pathStr + ":" + signatureLine)) {
            return;
        }
        violations.add(new Violation(ruleId, determineSeverity(ruleKey), ruleKey, message, pathStr,
                region, null, remediation, List.of()));
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
