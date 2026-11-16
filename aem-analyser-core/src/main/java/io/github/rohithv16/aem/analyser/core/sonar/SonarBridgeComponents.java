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
                new InvocationHandler() {
                    private RuleKey ruleKey;
                    private InputComponent inputComponent;
                    private org.sonar.api.batch.fs.TextRange textRange;
                    private String message;

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        if ("forRule".equals(name) && args != null && args.length > 0) {
                            this.ruleKey = (RuleKey) args[0];
                            return proxy;
                        }
                        if ("newLocation".equals(name)) {
                            return createNewIssueLocationProxy();
                        }
                        if ("newQuickFix".equals(name)) {
                            return createDummyProxy(org.sonar.api.batch.sensor.issue.fix.NewQuickFix.class);
                        }
                        if ("addQuickFix".equals(name) || "addLocation".equals(name) || "addFlow".equals(name)
                                || "gap".equals(name) || "overrideSeverity".equals(name) || "overrideImpact".equals(name)
                                || "setQuickFixAvailable".equals(name) || "setRuleDescriptionContextKey".equals(name)
                                || "setCodeVariants".equals(name) || "at".equals(name)) {
                            return proxy;
                        }
                        if ("save".equals(name)) {
                            if (ruleKey != null) {
                                String rKey = ruleKey.rule();
                                String ruleId = SONAR_RULE_PREFIX + rKey;
                                String pathStr = "";
                                if (inputComponent instanceof InputFile ifile) {
                                    pathStr = ifile.path() != null ? ifile.path().toString().replace('\\', '/') : ifile.filename();
                                    if (projectRoot != null && ifile.path() != null) {
                                        try {
                                            pathStr = projectRoot.toAbsolutePath().normalize().relativize(ifile.path().toAbsolutePath().normalize())
                                                    .toString().replace('\\', '/');
                                        } catch (Exception notRelativizable) {
                                            // A file outside the analysed root keeps its absolute
                                            // path: the finding is still reported, only not relative.
                                        }
                                    }
                                }
                                int startLine = (textRange != null && textRange.start() != null) ? textRange.start().line() : 1;
                                int startCol = (textRange != null && textRange.start() != null) ? textRange.start().lineOffset() : 0;
                                int endLine = (textRange != null && textRange.end() != null) ? textRange.end().line() : startLine;
                                int endCol = (textRange != null && textRange.end() != null) ? textRange.end().lineOffset() : 0;
                                TextRegion region = (startLine > 0)
                                        ? new TextRegion(startLine, startCol, endLine, endCol)
                                        : TextRegion.forWholeFile();

                                String sig = ruleId + ":" + pathStr + ":" + startLine;
                                if (reportedSignatures.add(sig)) {
                                    Severity severity = determineSeverity(rKey);
                                    violations.add(new Violation(
                                            ruleId,
                                            severity,
                                            rKey,
                                            message != null ? message : "Rule violation " + rKey,
                                            pathStr,
                                            region,
                                            null,
                                            "Follow SonarJava rule " + rKey + " standards.",
                                            List.of()
                                    ));
                                }
                            }
                            return null;
                        }
                        return defaultValue(method.getReturnType(), proxy);
                    }

                    private NewIssueLocation createNewIssueLocationProxy() {
                        return (NewIssueLocation) Proxy.newProxyInstance(
                                NewIssueLocation.class.getClassLoader(),
                                new Class<?>[]{NewIssueLocation.class},
                                (locProxy, locMethod, locArgs) -> {
                                    String locName = locMethod.getName();
                                    if ("on".equals(locName) && locArgs != null && locArgs.length > 0) {
                                        inputComponent = (InputComponent) locArgs[0];
                                        return locProxy;
                                    }
                                    if ("at".equals(locName) && locArgs != null && locArgs.length > 0) {
                                        textRange = (org.sonar.api.batch.fs.TextRange) locArgs[0];
                                        return locProxy;
                                    }
                                    if ("message".equals(locName) && locArgs != null && locArgs.length > 0) {
                                        message = String.valueOf(locArgs[0]);
                                        return locProxy;
                                    }
                                    return defaultValue(locMethod.getReturnType(), locProxy);
                                }
                        );
                    }
                }
        );
    }

    @SuppressWarnings("unchecked")
    private <T> T createDummyProxy(Class<T> clazz) {
        return (T) Proxy.newProxyInstance(
                clazz.getClassLoader(),
                new Class<?>[]{clazz},
                (proxy, method, args) -> defaultValue(method.getReturnType(), proxy)
        );
    }

    private Object defaultValue(Class<?> returnType, Object proxy) {
        if (returnType.isAssignableFrom(proxy.getClass())) {
            return proxy;
        }
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        return null;
    }

    @Override
    public Optional<RuleKey> getRuleKey(JavaCheck check) {
        if (check == null) {
            return Optional.empty();
        }
        Rule rule = check.getClass().getAnnotation(Rule.class);
        String key = (rule != null && rule.key() != null) ? rule.key() : check.getClass().getSimpleName();
        return Optional.of(RuleKey.of("java", key));
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
        String ruleId = SONAR_RULE_PREFIX + ruleKey;

        TextRegion region = primaryRegion(message);

        InputComponent comp = message.getInputComponent();
        String pathStr = "";
        if (comp instanceof InputFile ifile) {
            pathStr = ifile.path() != null ? ifile.path().toString().replace('\\', '/') : ifile.filename();
            if (projectRoot != null && ifile.path() != null) {
                try {
                    pathStr = projectRoot.toAbsolutePath().normalize().relativize(ifile.path().toAbsolutePath().normalize())
                            .toString().replace('\\', '/');
                } catch (Exception notRelativizable) {
                    // A file outside the analysed root keeps its absolute path: the finding is still
                    // reported, only not project-relative.
                }
            }
        }

        String sig = ruleId + ":" + pathStr + ":" + region.startLine();
        if (!reportedSignatures.add(sig)) {
            return;
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
                "Follow SonarJava best practice for rule " + ruleKey + ".",
                List.of()
        ));
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
        Rule rule = check.getClass().getAnnotation(Rule.class);
        String ruleKey = (rule != null && rule.key() != null) ? rule.key() : check.getClass().getSimpleName();
        String ruleId = SONAR_RULE_PREFIX + ruleKey;

        String pathStr = "";
        if (component instanceof InputFile ifile) {
            pathStr = ifile.path() != null ? ifile.path().toString().replace('\\', '/') : ifile.filename();
            if (projectRoot != null && ifile.path() != null) {
                try {
                    pathStr = projectRoot.toAbsolutePath().normalize().relativize(ifile.path().toAbsolutePath().normalize())
                            .toString().replace('\\', '/');
                } catch (Exception notRelativizable) {
                    // A file outside the analysed root keeps its absolute path: the finding is still
                    // reported, only not project-relative.
                }
            }
        }

        String sig = ruleId + ":" + pathStr + ":" + line;
        if (!reportedSignatures.add(sig)) {
            return;
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
                "Follow SonarJava best practice for rule " + ruleKey + ".",
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
