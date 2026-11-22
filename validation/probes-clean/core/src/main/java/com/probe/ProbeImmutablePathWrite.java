package com.probe;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * Clean twin of AEM-CLOUD-008: the same two writes, into /var instead of the immutable /apps and
 * /libs. Runtime state that a service may legitimately own.
 */
public final class ProbeImmutablePathWrite {

    public void writeToVar(ResourceResolver resolver) {
        resolver.getResource("/var/probe/config")
                .adaptTo(ModifiableValueMap.class)
                .put("enabled", Boolean.TRUE);
    }

    public void writeToVarState(Session session) throws Exception {
        Node node = session.getNode("/var/probe/settings");
        node.setProperty("mode", "on");
    }
}
