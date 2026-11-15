package io.github.rohithv16.aem.analyser.maven;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the documented Maven parameter surface without requiring a full Invoker reactor.
 * Maven's {@code @Parameter} annotation is not runtime-retained, so this asserts field parity and
 * inheritance; the generated plugin descriptor (built by maven-plugin-plugin) is the authoritative
 * annotation check.
 */
class MojoParameterTest {

    private static final List<String> SHARED_PARAMETERS = List.of(
            "projectRoot", "profiles", "threads", "maxFileSizeBytes", "timeoutSeconds",
            "ignoredServices", "ignoredConfigPaths", "dependencyJars", "configFile",
            "enableSonar", "sonarRules", "formats", "outputFile", "skip", "verbose",
            "generateHtml", "baseline", "updateBaseline", "baselineReport",
            "changedOnly", "since", "fix", "fixDryRun", "failOn", "minCoverage",
            "maxDuplication", "maxWarnings", "historyDirectory", "historyLimit");

    @Test
    void sharedParametersExistOnTheBaseMojo() {
        for (String name : SHARED_PARAMETERS) {
            assertTrue(fieldExists(AbstractAnalyserMojo.class, name),
                    name + " must be declared on AbstractAnalyserMojo");
        }
    }

    @Test
    void bothGoalsExtendTheSharedBase() {
        assertTrue(AbstractAnalyserMojo.class.isAssignableFrom(CheckMojo.class));
        assertTrue(AbstractAnalyserMojo.class.isAssignableFrom(ReportMojo.class));
    }

    @Test
    void checkGoalStillExposesFailureThresholds() {
        assertTrue(fieldExists(CheckMojo.class, "failOnError"));
        assertTrue(fieldExists(CheckMojo.class, "failOnWarning"));
    }

    private boolean fieldExists(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field ignored = current.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return false;
    }
}
