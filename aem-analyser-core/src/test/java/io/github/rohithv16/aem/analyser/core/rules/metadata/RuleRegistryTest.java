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

    /**
     * Issue #7: AEM-SEC-002 was tagged "security" without a CWE, so SARIF, --explain and the docs
     * card exposed an unclassified security finding. Both weakness classes the rule covers must be
     * named: open redirect (CWE-601) and SSRF (CWE-918).
     */
    @Test
    void openRedirectAndSsrfRuleCarriesBothCwes() {
        String cwe = RuleRegistry.forRule(RuleId.AEM_SEC_002).cwe();
        assertFalse(cwe == null || cwe.isBlank(), "AEM-SEC-002 must carry a CWE");
        assertTrue(cwe.contains("CWE-601"), cwe);
        assertTrue(cwe.contains("CWE-918"), cwe);
    }

    /** A rule tagged "security" is a weakness: it must name at least one CWE. */
    @Test
    void everySecurityTaggedRuleNamesACwe() {
        for (RuleDescriptor descriptor : RuleRegistry.all()) {
            if (descriptor.tags().contains("security")) {
                assertFalse(descriptor.cwe() == null || descriptor.cwe().isBlank(),
                        descriptor.id() + " is tagged security but carries no CWE");
                assertTrue(descriptor.cwe().contains("CWE-"), descriptor.id());
            }
        }
    }
}
