package com.probe;

import javax.servlet.Servlet;
import org.osgi.service.component.annotations.Component;

@Component(service = Servlet.class, property = "sling.servlet.paths=/bin/probe")
class ProbeServletOne implements Servlet {
    public void init(javax.servlet.ServletConfig config) { }
    public javax.servlet.ServletConfig getServletConfig() { return null; }
    public void service(javax.servlet.ServletRequest req, javax.servlet.ServletResponse res) { }
    public String getServletInfo() { return "probe"; }
    public void destroy() { }
}

@Component(service = Servlet.class, property = "sling.servlet.paths=/bin/probe")
class ProbeServletTwo implements Servlet {
    // Deliberately different from ProbeServletOne: AEM-DUP-010 must be demonstrated by the
    // compute() pair in ProbeComponents, not by identical servlet boilerplate.
    public void init(javax.servlet.ServletConfig config) { }
    public javax.servlet.ServletConfig getServletConfig() { return null; }
    public void service(javax.servlet.ServletRequest req, javax.servlet.ServletResponse res) {
        res.setContentType("text/plain");
    }
    public String getServletInfo() { return "probe-two"; }
    public void destroy() { }
}
