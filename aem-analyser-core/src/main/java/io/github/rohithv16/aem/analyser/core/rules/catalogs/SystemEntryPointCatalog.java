package io.github.rohithv16.aem.analyser.core.rules.catalogs;

import java.util.List;
import java.util.Set;

/**
 * FEAT-RULE-06: exhaustive 11-category system entry point catalog eliminating false
 * positives on framework-invoked components (whiteboard SPIs etc.).
 */
public final class SystemEntryPointCatalog {

    private static final List<String> ENTRY_POINT_INTERFACES = List.of(
            // 1. HTTP Servlets
            "javax.servlet.Servlet",
            "jakarta.servlet.Servlet",
            "org.apache.sling.api.servlets.SlingSafeMethodsServlet",
            "org.apache.sling.api.servlets.SlingAllMethodsServlet",
            // 2. Event Handlers
            "org.osgi.service.event.EventHandler",
            "org.apache.sling.api.resource.observation.ResourceChangeListener",
            "org.apache.sling.api.resource.observation.ExternalResourceChangeListener",
            // 3. Schedulers & Jobs
            "java.lang.Runnable",
            "org.apache.sling.event.jobs.consumer.JobConsumer",
            "org.apache.sling.event.jobs.JobConsumer",
            "org.apache.sling.event.jobs.consumer.JobExecutor",
            "org.apache.sling.event.jobs.JobExecutor",
            // 4. Servlet Filters
            "javax.servlet.Filter",
            "jakarta.servlet.Filter",
            // 5. Workflow Processes & Choosers
            "com.adobe.granite.workflow.exec.WorkflowProcess",
            "com.day.cq.workflow.exec.WorkflowProcess",
            "com.adobe.granite.workflow.exec.WorkflowParticipant",
            "com.adobe.granite.workflow.exec.ParticipantStepChooser",
            "com.day.cq.workflow.exec.ParticipantStepChooser",
            // 6. Replication Handlers
            "com.day.cq.replication.ReplicationPreprocessor",
            "com.day.cq.replication.TransportHandler",
            "com.day.cq.replication.ContentBuilder",
            "com.day.cq.replication.ReplicationQueueFilter",
            // 7. Health Checks
            "org.apache.felix.hc.api.HealthCheck",
            "org.apache.sling.hc.api.HealthCheck",
            // 8. Sling Post Operations
            "org.apache.sling.servlets.post.PostOperation",
            // 9. Rewriter Pipelines
            "org.apache.sling.rewriter.TransformerFactory",
            "org.apache.sling.rewriter.SerializerFactory",
            "org.apache.sling.rewriter.ProcessorFactory",
            "org.apache.sling.rewriter.GeneratorFactory",
            // 10/11. Whiteboard SPIs
            "org.apache.sling.sitemap.spi.generator.SitemapGenerator",
            "org.apache.sling.models.spi.Injector",
            "org.apache.sling.models.spi.InjectAnnotationProcessorFactory",
            "org.apache.sling.models.spi.InjectAnnotationProcessorFactory2",
            "org.apache.sling.models.export.spi.ModelExporter",
            "org.apache.sling.models.export.spi.ModelPostProcessor",
            "org.apache.sling.auth.core.spi.AuthenticationHandler",
            "org.apache.sling.auth.core.spi.AuthenticationInfoPostProcessor",
            "org.apache.sling.auth.core.spi.LoginModuleFactory",
            "com.day.cq.search.eval.PredicateEvaluator",
            "com.day.cq.dam.api.handler.AssetHandler",
            "com.day.cq.dam.api.handler.aem.AssetHandler",
            "org.apache.sling.caconfig.spi.ConfigurationOverrideProvider",
            "org.apache.sling.caconfig.spi.ConfigurationPersistenceStrategy2",
            "org.apache.sling.api.request.SlingRequestListener",
            "javax.servlet.ServletRequestListener",
            "jakarta.servlet.ServletRequestListener",
            "javax.servlet.ServletRequestAttributeListener",
            "javax.servlet.ServletContextListener",
            "jakarta.servlet.ServletContextListener",
            "javax.servlet.ServletContextAttributeListener",
            "jakarta.servlet.ServletContextAttributeListener",
            "javax.servlet.http.HttpSessionListener",
            "jakarta.servlet.http.HttpSessionListener",
            "org.apache.sling.commons.scheduler.Job",
            "org.apache.felix.webconsole.AbstractWebConsolePlugin",
            "org.apache.felix.webconsole.WebConsolePlugin",
            "org.osgi.framework.BundleActivator",
            "org.apache.jackrabbit.oak.spi.commit.CommitEditor",
            "org.apache.sling.jcr.registration.AbstractRepositoryRegistration",
            "com.adobe.cq.crypto.CryptoSupport",
            "org.apache.sling.engine.impl.filter.AbstractSlingFilter");

    private static final List<String> ENTRY_POINT_INTERFACES_PREFIX = List.of(
            // Trait-based SPI interfaces that are stable prefixes
            "org.apache.sling.models.spi.",
            "org.apache.sling.caconfig.spi.",
            "org.apache.sling.sitemap.spi.",
            "com.day.cq.dam.api.handler.",
            "com.day.cq.replication.impl.transport.");

    private static final List<String> ENTRY_POINT_SUPERCLASSES = List.of(
            "org.apache.sling.api.servlets.SlingSafeMethodsServlet",
            "org.apache.sling.api.servlets.SlingAllMethodsServlet",
            "javax.servlet.http.HttpServlet",
            "jakarta.servlet.http.HttpServlet",
            "org.apache.felix.webconsole.AbstractWebConsolePlugin",
            "com.day.cq.dam.core.process.AbstractStepProcess",
            "com.adobe.granite.workflow.exec.AbstractStepProcess");

    private static final List<String> ENTRY_POINT_PROPERTY_PREFIXES = List.of(
            "sling.servlet.",
            "sling.filter.",
            "osgi.http.whiteboard.servlet.pattern",
            "osgi.http.whiteboard.filter.pattern",
            "osgi.http.whiteboard.servlet.async-supported",
            "felix.webconsole.",
            "hc.",
            "sling.post.operation",
            "chooser.label",
            "event.topics");

    private SystemEntryPointCatalog() {
    }

    public static boolean isSystemEntryPoint(Set<String> superClassesAndInterfaces,
                                             Set<String> componentProperties) {
        for (String type : superClassesAndInterfaces) {
            if (type == null) {
                continue;
            }
            if (ENTRY_POINT_INTERFACES.contains(type) || ENTRY_POINT_SUPERCLASSES.contains(type)) {
                return true;
            }
            if (ENTRY_POINT_INTERFACES_PREFIX.stream().anyMatch(type::startsWith)) {
                return true;
            }
        }
        for (String property : componentProperties) {
            String key = property.contains("=") ? property.substring(0, property.indexOf('=')).strip() : property;
            for (String prefix : ENTRY_POINT_PROPERTY_PREFIXES) {
                if (key.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
