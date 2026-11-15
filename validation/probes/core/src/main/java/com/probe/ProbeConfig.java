package com.probe;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Probe configuration")
@interface ProbeOcd {
    String used() default "x";
    String unusedProp() default "y";
}

@Designate(ocd = ProbeOcd.class)
@Component(service = Runnable.class, immediate = true)
class ProbeConfigComponent implements Runnable {
    @Activate
    void activate(ProbeOcd config) {
        config.used();
    }
    public void run() { }
}
