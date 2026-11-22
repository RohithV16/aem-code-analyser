package com.probe.missing;

import org.osgi.service.component.annotations.Component;

/** Clean twin: the PID this configuration targets is now a real component. */
@Component(configurationPid = "com.probe.missing.Service")
public class Service {

    public void run() {
    }
}
