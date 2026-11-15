package io.github.rohithv16.aem.analyser.core.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetatypeNameNormalizerTest {

    @Test
    void singleUnderscoresBecomeDots() {
        assertEquals("webconsole.configurationFactory.nameHint",
                MetatypeNameNormalizer.normalizeMethodName("webconsole_configurationFactory_nameHint", null));
        assertEquals("endpoint.url", MetatypeNameNormalizer.normalizeMethodName("endpoint_url", null));
    }

    @Test
    void leadingUnderscoreIsStrippedOnce() {
        assertEquals("default", MetatypeNameNormalizer.normalizeMethodName("_default", null));
        assertEquals("import", MetatypeNameNormalizer.normalizeMethodName("_import", null));
        assertEquals("class", MetatypeNameNormalizer.normalizeMethodName("_class", null));
    }

    @Test
    void doubleUnderscoreBecomesSingleUnderscore() {
        assertEquals("my_custom_prop", MetatypeNameNormalizer.normalizeMethodName("my__custom__prop", null));
    }

    @Test
    void dollarEscapes() {
        assertEquals("a$b", MetatypeNameNormalizer.normalizeMethodName("a$$b", null));
        assertEquals("a-b", MetatypeNameNormalizer.normalizeMethodName("a$_$b", null));
    }

    @Test
    void explicitAttributeIdAlwaysWins() {
        assertEquals("custom.id", MetatypeNameNormalizer.normalizeMethodName("anything_else", "custom.id"));
        assertEquals("custom.id", MetatypeNameNormalizer.normalizeMethodName("_weird__name", " custom.id "));
    }

    @Test
    void nullAndEmptyHandled() {
        assertNull(MetatypeNameNormalizer.normalizeMethodName(null, null));
        assertEquals("", MetatypeNameNormalizer.normalizeMethodName("", null));
    }
}
