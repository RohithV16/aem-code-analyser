package io.github.rohithv16.aem.analyser.core.sonar;

import org.sonar.check.Rule;
import org.sonar.plugins.java.api.JavaFileScanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registry of official SonarSource Java checks curated for enterprise AEM development.
 */
public final class SonarRuleRegistry {

    private static final Map<String, List<Supplier<JavaFileScanner>>> RULE_FACTORIES = new HashMap<>();

    static {
        // Code smells & clean code
        register(org.sonar.java.checks.SystemOutOrErrUsageCheck::new); // S106
        register(org.sonar.java.checks.unused.UnusedPrivateMethodCheck::new); // S1144
        register(org.sonar.java.checks.unused.UnusedMethodParameterCheck::new); // S1172
        register(org.sonar.java.checks.unused.UnusedLocalVariableCheck::new); // S1481
        register(org.sonar.java.checks.EmptyMethodsCheck::new); // S1186
        register(org.sonar.java.checks.UtilityClassWithPublicConstructorCheck::new); // S1118
        register(org.sonar.java.checks.naming.BadConstantNameCheck::new); // S115
        register(org.sonar.java.checks.naming.BadFieldNameCheck::new); // S116
        register(org.sonar.java.checks.naming.BadMethodNameCheck::new); // S100
        register(org.sonar.java.checks.naming.BadClassNameCheck::new); // S101
        register(org.sonar.java.checks.InterfaceAsConstantContainerCheck::new); // S1214
        register(org.sonar.java.checks.StringLiteralDuplicatedCheck::new); // S1192
        register(org.sonar.java.checks.ModifiersOrderCheck::new); // S2204
        register(org.sonar.java.checks.OverrideAnnotationCheck::new); // S1161
        register(org.sonar.java.checks.TodoTagPresenceCheck::new); // S1135
        register(org.sonar.java.checks.WildcardImportsShouldNotBeUsedCheck::new); // S2208
        register(org.sonar.java.checks.UselessImportCheck::new); // S1128
        register(io.github.rohithv16.aem.analyser.core.sonar.checks.DuplicateImportCheck::new); // S1128
        register(org.sonar.java.checks.IdenticalCasesInSwitchCheck::new); // S1871
        register(org.sonar.java.checks.RawTypeCheck::new); // S3740
        register(org.sonar.java.checks.CollectionIsEmptyCheck::new); // S1155
        register(org.sonar.java.checks.BooleanLiteralCheck::new); // S1125
        register(org.sonar.java.checks.ReturnOfBooleanExpressionsCheck::new); // S1126
        register(org.sonar.java.checks.ImmediatelyReturnedVariableCheck::new); // S1488
        register(org.sonar.java.checks.StandardCharsetsConstantsCheck::new); // S4719
        register(org.sonar.java.checks.ArrayDesignatorOnVariableCheck::new); // S1197
        register(org.sonar.java.checks.NonShortCircuitLogicCheck::new); // S2178

        // Concurrency & multithreading
        register(org.sonar.java.checks.InterruptedExceptionCheck::new); // S2142
        register(org.sonar.java.checks.synchronization.DoubleCheckedLockingCheck::new); // S2168
        register(org.sonar.java.checks.ThreadSleepCheck::new); // S2276

        // Reliability, exceptions & resource safety
        register(org.sonar.java.checks.TryWithResourcesCheck::new); // S2095
        register(org.sonar.java.checks.CollectionInappropriateCallsCheck::new); // S2114
        register(org.sonar.java.checks.CallToDeprecatedMethodCheck::new); // S1874
        register(org.sonar.java.checks.CastArithmeticOperandCheck::new); // S2184
        register(org.sonar.java.checks.RawExceptionCheck::new); // S112
        register(org.sonar.java.checks.ThrowsFromFinallyCheck::new); // S1163
        register(org.sonar.java.checks.ReturnInFinallyCheck::new); // S1143
        register(org.sonar.java.checks.SystemExitCalledCheck::new); // S1147
        register(org.sonar.java.checks.NullShouldNotBeUsedWithOptionalCheck::new); // S2789
        register(org.sonar.java.checks.ReturnEmptyArrayNotNullCheck::new); // S1168
        register(org.sonar.java.checks.NullCheckWithInstanceofCheck::new); // S4201
        register(org.sonar.java.checks.CatchUsesExceptionWithContextCheck::new); // S1166
        register(org.sonar.java.checks.LoggedRethrownExceptionsCheck::new); // S2139
        register(org.sonar.java.checks.CatchOfThrowableOrErrorCheck::new); // S1181
        register(org.sonar.java.checks.NestedTryCatchCheck::new); // S1141
        register(org.sonar.java.checks.RedundantThrowsDeclarationCheck::new); // S1130

        // Security smells
        register(org.sonar.java.checks.WeakSSLContextCheck::new); // S4423
        register(org.sonar.java.checks.HardCodedSecretCheck::new); // S6418
        register(org.sonar.java.checks.AccessibilityChangeCheck::new); // S3011
    }

    private static void register(Supplier<JavaFileScanner> supplier) {
        JavaFileScanner scanner = supplier.get();
        Rule rule = scanner.getClass().getAnnotation(Rule.class);
        if (rule != null && rule.key() != null) {
            addFactory(rule.key().toUpperCase(), supplier);
            addFactory("S" + rule.key().toUpperCase().replace("S", ""), supplier);
            addFactory("JAVA:" + rule.key().toUpperCase(), supplier);
        }
        addFactory(scanner.getClass().getSimpleName().toUpperCase(), supplier);
    }

    private static void addFactory(String key, Supplier<JavaFileScanner> supplier) {
        RULE_FACTORIES.computeIfAbsent(key, k -> new ArrayList<>()).add(supplier);
    }

    public static List<JavaFileScanner> createDefaultChecks() {
        Map<Class<?>, JavaFileScanner> map = new HashMap<>();
        for (List<Supplier<JavaFileScanner>> list : RULE_FACTORIES.values()) {
            for (Supplier<JavaFileScanner> supplier : list) {
                JavaFileScanner scanner = supplier.get();
                map.putIfAbsent(scanner.getClass(), scanner);
            }
        }
        return new ArrayList<>(map.values());
    }

    public static List<JavaFileScanner> createChecksFor(String rulesetFilter) {
        if (rulesetFilter == null || rulesetFilter.isBlank() || "default".equalsIgnoreCase(rulesetFilter)) {
            return createDefaultChecks();
        }
        String[] tokens = rulesetFilter.split("[,;\\s]+");
        Map<Class<?>, JavaFileScanner> map = new HashMap<>();
        for (String token : tokens) {
            List<Supplier<JavaFileScanner>> list = RULE_FACTORIES.get(token.trim().toUpperCase());
            if (list != null) {
                for (Supplier<JavaFileScanner> supplier : list) {
                    JavaFileScanner scanner = supplier.get();
                    map.putIfAbsent(scanner.getClass(), scanner);
                }
            }
        }
        if (map.isEmpty()) {
            return createDefaultChecks();
        }
        return new ArrayList<>(map.values());
    }
}
