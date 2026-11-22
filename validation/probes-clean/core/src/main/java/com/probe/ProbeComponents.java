package com.probe;

import org.apache.sling.api.SlingHttpServletResponse;
import org.osgi.service.component.annotations.Component;

/**
 * Clean twin of the positive probe set: the same component shapes with every defect fixed.
 * A rule that still fires here is a false positive, not a probe success.
 */
@Component(service = Runnable.class)
class SchedulerProbe implements Runnable {
    public void run() { }
}

@Component(service = Runnable.class)
class RedirectProbe implements Runnable {
    public void run() { }

    void redirect(javax.servlet.http.HttpServletRequest request, SlingHttpServletResponse response)
            throws java.io.IOException {
        response.sendRedirect("/content/probe/thanks.html");
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
    // Clean twin: same shape, different arithmetic, so it is not a clone.
    public int compute(int input) {
        int total = 1;
        for (int i = 0; i < input; i++) {
            if (i % 2 == 0) { total += 2 * i; } else { total -= 3 * i; }
        }
        return total;
    }
}
