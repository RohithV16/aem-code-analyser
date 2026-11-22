package com.probe;

import org.osgi.service.component.annotations.Component;

/** Clean twin: the component no longer declares an interface nobody provides. */
@Component
class UnprovidedReferenceProbe {

    public String go() {
        return "ok";
    }
}
