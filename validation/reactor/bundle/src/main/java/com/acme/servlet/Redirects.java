package com.acme.servlet;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;
import org.osgi.service.component.annotations.Component;
import com.acme.config.AcmeConstants;

@Component(service = Servlet.class, property = {"sling.servlet.resourceTypes=acme/redirect"})
public class Redirects {
    private static final String SAFE_TARGET = "/content/acme/en.html";

    void safe(HttpServletResponse response) { response.sendRedirect(SAFE_TARGET); }
    void qualified(HttpServletResponse response) { response.sendRedirect(AcmeConstants.PID); }
    void unsafe(HttpServletResponse response, String to) { response.sendRedirect(to); }
}
