package com.probe;

import javax.servlet.Servlet;
import org.osgi.service.component.annotations.Component;

@Component(service = Servlet.class,
        property = {"sling.servlet.resourceTypes=probe/components/probe",
                "sling.servlet.extensions=html"})
class ProbeServletOne implements Servlet {
    public void init(javax.servlet.ServletConfig config) { }
    public javax.servlet.ServletConfig getServletConfig() { return null; }
    public void service(javax.servlet.ServletRequest req, javax.servlet.ServletResponse res) { }
    public String getServletInfo() { return "probe"; }
    public void destroy() { }
}

@Component(service = Servlet.class,
        property = {"sling.servlet.resourceTypes=probe/components/classic",
                "sling.servlet.extensions=json"})
class ProbeServletTwo implements Servlet {
    public void init(javax.servlet.ServletConfig config) { }
    public javax.servlet.ServletConfig getServletConfig() { return null; }
    public void service(javax.servlet.ServletRequest req, javax.servlet.ServletResponse res) {
        res.setContentType("text/plain");
    }
    public String getServletInfo() { return "probe-two"; }
    public void destroy() { }
}
