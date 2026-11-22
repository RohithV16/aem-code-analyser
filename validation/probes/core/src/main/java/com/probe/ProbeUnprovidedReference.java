package com.probe;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/** Declared in the project but registered by no component: AEM-QUAL-008. */
interface UnprovidedService {
    String name();
}

@Component(service = UnprovidedReferenceProbe.class)
class UnprovidedReferenceProbe {

    @Reference
    private UnprovidedService unprovidedService;

    public String go() {
        return unprovidedService.name();
    }
}
