package io.github.rohithv16.aem.analyser.core.rules.catalogs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformPidCatalogTest {

    @Test
    void prefixMatchRequiresSegmentBoundary() {
        assertTrue(PlatformPidCatalog.isPlatformPid("com.adobe.aem.sample.MyService"));
        assertTrue(PlatformPidCatalog.isPlatformPid("com.adobe.aem"));
        assertFalse(PlatformPidCatalog.isPlatformPid("com.adobe.aemfoo.MyService"),
                "com.adobe.aem must not match a different package segment");
    }

    @Test
    void packagePrefixesStillMatchDescendants() {
        assertTrue(PlatformPidCatalog.isPlatformPid("com.adobe.cq.wcm.core.components.internal.servlets.TableOfContentsFilter"));
        assertTrue(PlatformPidCatalog.isPlatformPid("io.wcm.handler.link.impl.LinkHandlerImpl"));
    }
}
