package io.github.rohithv16.aem.analyser.core.parser.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFilePidResolverTest {

    @Test
    void splitsTildeFactoryForCfgJson() {
        ConfigFilePidResolver.ResolvedPid resolved =
                ConfigFilePidResolver.resolve("com.example.core.service.SearchServiceImpl~en-customer", true);
        assertEquals("com.example.core.service.SearchServiceImpl", resolved.pid());
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
    void splitsTildeFactoryWithHyphenatedPid() {
        ConfigFilePidResolver.ResolvedPid resolved =
                ConfigFilePidResolver.resolve("my-service.pid~tenant-a", true);
        assertEquals("my-service.pid", resolved.pid());
        assertEquals("tenant-a", resolved.factorySubname());
        assertTrue(resolved.isFactory());
        assertFalse(resolved.ambiguousHyphen());
    }

    @Test
    void acceptsHyphenInLeadingPidSegment() {
        ConfigFilePidResolver.ResolvedPid resolved =
                ConfigFilePidResolver.resolve("my-service.pid", true);
        assertEquals("my-service.pid", resolved.pid());
        assertFalse(resolved.isFactory());
        assertTrue(ConfigFilePidResolver.isValidPid("my-service.pid"));
    }

    @Test
    void resolvesLegacyHyphenatedPidWithFactorySuffix() {
        ConfigFilePidResolver.ResolvedPid resolved =
                ConfigFilePidResolver.resolve("my-service.pid-instance", false);
        assertEquals("my-service.pid", resolved.pid());
        assertEquals("instance", resolved.factorySubname());
        assertTrue(resolved.isFactory());
        assertTrue(resolved.ambiguousHyphen());
    }

    /**
     * Regression: a numeric suffix used to be treated as an unambiguous factory instance, so a
     * component whose real PID is "com.example.Service-1" got an AEM-DEAD-001 false positive and
     * graph-time disambiguation never ran.
     */
    @Test
    void numericLegacyFactorySuffixIsAmbiguous() {
        ConfigFilePidResolver.ResolvedPid resolved =
                ConfigFilePidResolver.resolve("com.example.Service-1", false);
        assertEquals("com.example.Service", resolved.pid());
        assertEquals("1", resolved.factorySubname());
        assertTrue(resolved.ambiguousHyphen(),
                "a numeric suffix may belong to a hyphenated PID and must be disambiguated at graph time");
    }

    @Test
    void stripExtensionHandlesEverySupportedFormat() {
        assertEquals("com.example.Foo", ConfigFilePidResolver.stripExtension("com.example.Foo.cfg.json"));
        assertEquals("com.example.Foo", ConfigFilePidResolver.stripExtension("com.example.Foo.content.xml"));
        assertEquals("com.example.Foo", ConfigFilePidResolver.stripExtension("com.example.Foo.config"));
        assertEquals("com.example.Foo", ConfigFilePidResolver.stripExtension("com.example.Foo.cfg"));
        assertEquals("com.example.Foo", ConfigFilePidResolver.stripExtension("com.example.Foo"));
    }

    @Test
    void rejectsSingleWordPidGrammar() {
        assertFalse(ConfigFilePidResolver.isValidPid("lombok"));
        assertTrue(ConfigFilePidResolver.isValidPid("lombok.config.impl"));
        ConfigFilePidResolver.ResolvedPid resolved = ConfigFilePidResolver.resolve("lombok", true);
        assertEquals(null, resolved.pid());
    }
}
