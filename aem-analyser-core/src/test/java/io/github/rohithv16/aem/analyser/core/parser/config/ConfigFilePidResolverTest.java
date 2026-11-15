package io.github.rohithv16.aem.analyser.core.parser.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFilePidResolverTest {

    @Test
    void splitsTildeFactoryForCfgJson() {
        ConfigFilePidResolver.ResolvedPid resolved =
                ConfigFilePidResolver.resolve("com.mandg.core.service.ElasticSearchImpl~en-customer", true);
        assertEquals("com.mandg.core.service.ElasticSearchImpl", resolved.pid());
        assertEquals("en-customer", resolved.factorySubname());
        assertTrue(resolved.isFactory());
    }

    @Test
    void neverSplitsHyphenForCfgJson() {
        ConfigFilePidResolver.ResolvedPid resolved =
                ConfigFilePidResolver.resolve("com.example.core.service.Some-service", true);
        assertEquals("com.example.core.service.Some-service", resolved.pid());
        assertFalse(resolved.isFactory());
    }

    @Test
    void splitsHyphenForLegacyConfig() {
        ConfigFilePidResolver.ResolvedPid resolved =
                ConfigFilePidResolver.resolve("org.apache.sling.commons.log.LogManager.factory.config-1", false);
        assertEquals("org.apache.sling.commons.log.LogManager.factory.config", resolved.pid());
        assertEquals("1", resolved.factorySubname());
        assertTrue(resolved.isFactory());
    }

    @Test
    void hyphenWithDottedSuffixIsAmbiguous() {
        ConfigFilePidResolver.ResolvedPid resolved =
                ConfigFilePidResolver.resolve("com.day.cq.foo-bar.1.0", false);
        assertTrue(resolved.ambiguousHyphen());
    }

    @Test
    void rejectsSingleWordPidGrammar() {
        assertFalse(ConfigFilePidResolver.isValidPid("lombok"));
        assertTrue(ConfigFilePidResolver.isValidPid("lombok.config.impl"));
        ConfigFilePidResolver.ResolvedPid resolved = ConfigFilePidResolver.resolve("lombok", true);
        assertEquals(null, resolved.pid());
    }
}
