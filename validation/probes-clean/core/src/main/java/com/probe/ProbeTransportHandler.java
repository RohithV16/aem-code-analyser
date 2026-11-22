package com.probe;

/**
 * Clean twin of AEM-CLOUD-010: no custom replication transport. A class that happens to travel with
 * the bundle is not a transport customisation.
 */
public final class ProbeTransportHandler {

    public String describe() {
        return "no transport customisation";
    }
}
