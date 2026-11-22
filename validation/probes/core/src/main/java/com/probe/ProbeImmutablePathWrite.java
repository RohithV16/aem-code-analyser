package com.probe;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * Probe for AEM-CLOUD-008: two runtime writes into the areas AEM as a Cloud Service keeps
 * immutable - one chained through a {@code ModifiableValueMap}, one through a node variable.
 */
public final class ProbeImmutablePathWrite {

    public void writeToApps(ResourceResolver resolver) {
        resolver.getResource("/apps/probe/config")
                .adaptTo(ModifiableValueMap.class)
                .put("enabled", Boolean.TRUE);
    }

    public void writeToLibs(Session session) throws Exception {
        Node node = session.getNode("/libs/probe/settings");
        node.setProperty("mode", "on");
    }
}
