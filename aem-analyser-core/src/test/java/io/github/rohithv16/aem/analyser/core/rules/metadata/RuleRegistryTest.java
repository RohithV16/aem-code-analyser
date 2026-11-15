package io.github.rohithv16.aem.analyser.core.rules.metadata;

import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleRegistryTest {

    @Test
    void everyRuleIdHasCompleteMetadata() {
        assertEquals(RuleId.values().length, RuleRegistry.all().size());
        for (RuleId rule : RuleId.values()) {
            RuleDescriptor descriptor = RuleRegistry.forRule(rule);
            assertEquals(rule.id(), descriptor.id());
            assertEquals(rule.defaultLevel(), descriptor.defaultSeverity());
            assertFalse(descriptor.shortDescription().isBlank(), rule.id());
            assertFalse(descriptor.helpUri().isBlank(), rule.id());
            assertFalse(descriptor.tags().isEmpty(), rule.id());
            assertFalse(descriptor.remediation().isBlank(), rule.id());
            assertTrue(descriptor.effortMinutes() > 0, rule.id());
        }
    }

    @Test
    void lookupIsCaseInsensitiveAndUnknownIdsFallBack() {
        assertTrue(RuleRegistry.find("aem-dead-002a").isPresent());
        assertEquals(RuleId.AEM_DEAD_002A.id(), RuleRegistry.find("aem-dead-002a").orElseThrow().id());

        assertTrue(RuleRegistry.find("java:S1186").isEmpty());
        RuleDescriptor sonar = RuleRegistry.get("java:S1186");
        assertEquals(RuleType.CODE_SMELL, sonar.type());
        assertTrue(sonar.tags().contains("sonar"));
    }

    @Test
    void securityRulesCarryCweMetadata() {
        assertEquals("CWE-79", RuleRegistry.forRule(RuleId.AEM_SEC_001).cwe());
        assertEquals(RuleType.VULNERABILITY, RuleRegistry.forRule(RuleId.AEM_SEC_001).type());
    }
}
