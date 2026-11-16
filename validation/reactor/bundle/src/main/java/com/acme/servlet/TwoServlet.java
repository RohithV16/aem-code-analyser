package com.acme.servlet;
import javax.servlet.Servlet;
import org.osgi.service.component.annotations.Component;
@Component(service = Servlet.class, property = {"sling.servlet.paths=/bin/acme/dup", "service.ranking=0"})
public class TwoServlet { }
