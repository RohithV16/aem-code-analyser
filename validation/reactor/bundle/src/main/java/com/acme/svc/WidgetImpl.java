package com.acme.svc;
import org.osgi.service.component.annotations.Component;
@Component(service = Widget.class, immediate = true)
public class WidgetImpl implements Widget { public String render() { return "w"; } }
