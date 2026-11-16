package com.acme.svc;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
@Component(immediate = true)
public class WidgetConsumer {
    @Reference private Widget widget;
    public String go() { return widget.render(); }
}
