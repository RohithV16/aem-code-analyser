package com.acme.servlet;
import javax.servlet.Servlet;
import org.osgi.service.component.annotations.Component;
@Component(service = Servlet.class, property = {"sling.servlet.resourceTypes=acme/child"})
public class ChildServlet extends Base { }
