package io.github.rohithv16.aem.analyser.core.rules.catalogs;

import java.util.List;
import java.util.Set;

/**
 * Comprehensive OSGi / Sling framework property whitelist used by rule AEM-DEAD-002A.
 * Properties consumed directly by the framework, Sling Engine, Quartz scheduler or
 * Felix Web Console are not declared in component Metatype interfaces.
 */
public final class FrameworkPropertyWhitelist {

    private static final Set<String> EXACT_KEYS = Set.of(
            "service.ranking", "service.description", "service.vendor", "service.pid",
            "service.bundleLocation", "service.factoryPid", "service.id", "service.scope",
            "webconsole.configurationFactory.nameHint",
            "sling.servlet.paths", "sling.servlet.resourceTypes", "sling.servlet.selectors",
            "sling.servlet.extensions", "sling.servlet.methods", "sling.servlet.prefix",
            "sling.auth.requirements",
            "sling.filter.scope", "sling.filter.order", "sling.filter.pattern",
            "scheduler.expression", "scheduler.period", "scheduler.concurrent",
            "scheduler.name", "scheduler.runOn", "scheduler.readOnly",
            "event.topics", "event.filter",
            "hc.tags", "hc.name", "hc.mbean",
            "felix.webconsole.label", "felix.webconsole.title", "felix.webconsole.category",
            "sling.post.operation",
            "osgi.http.whiteboard.servlet.pattern",
            "osgi.http.whiteboard.filter.pattern",
            "osgi.http.whiteboard.servlet.async-supported",
            "osgi.http.whiteboard.filter.dispatcher",
            "osgi.http.whiteboard.listener",
            "osgi.http.whiteboard.context.select",
            "osgi.http.whiteboard.context.path",
            "jmx.objectname",
            "README");

    private static final List<String> PREFIX_KEYS = List.of(
            ":",
            "_comment",
            "sling.servlet.",
            "sling.filter.",
            "sling.auth.",
            "scheduler.",
            "service.",
            "felix.webconsole.",
            "hc.",
            "osgi.http.whiteboard.",
            "event.",
            "jmx.",
            "org.apache.felix.",
            "configuration.",
            "webconsole.");

    private FrameworkPropertyWhitelist() {
    }

    public static boolean isWhitelisted(String key) {
        if (key == null) {
            return true;
        }
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        if (EXACT_KEYS.contains(normalized)) {
            return true;
        }
        return PREFIX_KEYS.stream().anyMatch(normalized::startsWith);
    }
}
