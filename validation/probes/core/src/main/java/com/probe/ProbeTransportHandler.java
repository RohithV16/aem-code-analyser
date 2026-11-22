package com.probe;

import com.day.cq.replication.TransportHandler;

/**
 * Probe for AEM-CLOUD-010: a custom replication transport. AEM as a Cloud Service never invokes it,
 * the content is distributed by the Adobe-hosted pipeline service instead.
 */
public final class ProbeTransportHandler implements TransportHandler {

    @Override
    public void deliver(com.day.cq.replication.TransportContext context,
                        com.day.cq.replication.ReplicationTransaction transaction) {
        // A custom transport configured by /etc/replication/agents.author/zzprobe.
    }
}
