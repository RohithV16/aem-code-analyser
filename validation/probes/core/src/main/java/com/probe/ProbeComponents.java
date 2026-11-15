package com.probe;

import javax.jcr.Session;
import org.apache.commons.lang.StringUtils;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.jcr.api.SlingRepository;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import com.day.cq.wcm.api.PageManager;

interface DeadServiceApi { }

@Component(service = DeadServiceApi.class, immediate = true)
class DeadServiceImpl implements DeadServiceApi { }

@Component(immediate = false)
class DelayedComponent { }

@Component(service = Runnable.class)
class SchedulerProbe implements Runnable {
    @Reference private Scheduler scheduler;
    public void run() { }
}

@Component(service = Runnable.class)
class ResolverFieldProbe implements Runnable {
    @Reference private ResourceResolver resolver;
    public void run() { }
}

@Component(service = Runnable.class)
class PageManagerFieldProbe implements Runnable {
    @Reference private PageManager pageManager;
    public void run() { }
}

@Component(service = Runnable.class)
class AdminProbe implements Runnable {
    @Reference private ResourceResolverFactory factory;
    public void run() { factory.getAdministrativeResourceResolver(null); }
}

@Component(service = Runnable.class)
class ResolverLeakProbe implements Runnable {
    @Reference private ResourceResolverFactory factory;
    public void run() {
        try {
            ResourceResolver leaked = factory.getServiceResourceResolver(
                    java.util.Map.of(ResourceResolverFactory.SUBSERVICE, "probe-leak"));
            leaked.commit();
        } catch (Exception ignored) { }
    }
}

@Component(service = Runnable.class)
class SessionLeakProbe implements Runnable {
    @Reference private SlingRepository repository;
    public void run() {
        try {
            Session session = repository.login(
                    new javax.jcr.SimpleCredentials("admin", "admin".toCharArray()));
            session.save();
        } catch (Exception ignored) { }
    }
}

@Component(service = Runnable.class)
class QueryProbe implements Runnable {
    @Reference private SlingRepository repository;
    public void run() {
        try {
            Session session = repository.login(new javax.jcr.SimpleCredentials("u", "p".toCharArray()));
            session.getWorkspace().getQueryManager().createQuery("select * from nt:base", "JCR-SQL2");
            session.getWorkspace().getQueryManager().createQuery("select * from [cq:Page]", "JCR-SQL2");
        } catch (Exception ignored) { }
    }
}

@Component(service = Runnable.class)
class RedirectProbe implements Runnable {
    public void run() { }
    void redirect(javax.servlet.http.HttpServletRequest request, SlingHttpServletResponse response) throws java.io.IOException {
        response.sendRedirect(request.getParameter("next"));
    }
}

class DuplicateOne {
    public int compute(int input) {
        int total = 0;
        for (int i = 0; i < input; i++) {
            if (i % 2 == 0) { total += i; } else { total -= i; }
        }
        return total;
    }
}

class DuplicateTwo {
    public int compute(int input) {
        int total = 0;
        for (int i = 0; i < input; i++) {
            if (i % 2 == 0) { total += i; } else { total -= i; }
        }
        return total;
    }
}
