package com.example.fixture;

import javax.servlet.Servlet;

import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

/**
 * Smoke fixture: one of two servlets claiming the same path at the same service.ranking, which
 * AEM-DUP-001 reports as an ERROR. It never has to compile — the analyser parses sources only.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/fixture/duplicate",
                "sling.servlet.methods=GET"
        })
public class AlphaServlet extends SlingSafeMethodsServlet {
}
