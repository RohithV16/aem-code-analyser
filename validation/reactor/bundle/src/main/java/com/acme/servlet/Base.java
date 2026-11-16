package com.acme.servlet;
import org.osgi.service.component.annotations.Reference;
import com.acme.svc.Widget;
public abstract class Base {
    @Reference protected Widget widget;
}
