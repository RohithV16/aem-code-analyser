package com.example.fixture;

import javax.servlet.Servlet;

import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

/**
 * Smoke fixture: the second registrant for {@code /bin/fixture/duplicate}, with the default
 * service.ranking (0) that {@link AlphaServlet} also uses.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/fixture/duplicate",
                "sling.servlet.methods=GET"
        })
public class BetaServlet extends SlingSafeMethodsServlet {
}

// changed for the cache proof

// proof: a content change must invalidate the task

// proof: a content change must invalidate the task

// x17271

// y9944

// proof: a content change must invalidate the task
