package io.github.rohithv16.aem.analyser.core.rules.catalogs;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * FEAT-RULE-02: curated catalog of standard platform PIDs across AEM, Sling, Oak,
 * Granite and common community modules. Supports exact matches and {@code prefix.*} wildcards.
 */
public final class PlatformPidCatalog {

    private static final List<String> EXACT_PIDS = List.of(
            // Apache Sling
            "org.apache.sling.jcr.repoinit.RepositoryInitializer",
            "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended",
            "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl",
            "org.apache.sling.commons.log.LogManager",
            "org.apache.sling.commons.log.LogManager.factory.config",
            "org.apache.sling.commons.log.LogManager.factory.writer",
            "org.apache.sling.jcr.resource.internal.JcrResourceResolverFactoryImpl",
            "org.apache.sling.dynamicinclude.Configuration",
            "org.apache.sling.security.impl.ReferrerFilter",
            "org.apache.sling.engine.impl.request.InitResourceFilter",
            "org.apache.sling.servlets.resolver.internal.SlingServletResolver",
            "org.apache.sling.servlets.post.SlingPostServlet",
            "org.apache.sling.installer.core.impl.console.InstallableResourceWebConsolePlugin",
            "org.apache.sling.datasource.DataSourceFactory",
            "org.apache.sling.jcr.oak.server.OakServer",
            "org.apache.sling.discovery.impl.Config",
            "org.apache.sling.engine.impl.auth.SlingAuthenticator",
            "org.apache.sling.auth.core.impl.SlingAuthenticator",
            "org.apache.sling.auth.core.impl.AuthenticationHandlerHolder",
            // Apache Jackrabbit Oak
            "org.apache.jackrabbit.oak.security.user.UserConfigurationImpl",
            "org.apache.jackrabbit.oak.security.authentication.AuthenticationConfigurationImpl",
            "org.apache.jackrabbit.oak.spi.security.authorization.accesscontrol.AccessControlConfigurationImpl",
            "org.apache.jackrabbit.oak.plugins.index.property.PropertyIndexEditorProvider",
            "org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexEditorProvider",
            "org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexProviderService",
            "org.apache.jackrabbit.oak.segment.SegmentNodeStoreService",
            "org.apache.jackrabbit.oak.repository.RepositoryImpl",
            // Adobe Granite / Day CQ
            "com.day.cq.wcm.mobile.core.impl.MobileEmulatorProvider",
            "com.adobe.granite.cors.impl.CORSPolicyImpl",
            "com.adobe.granite.cors.impl.DefaultCORSPolicyImpl",
            "com.day.cq.rewriter.linkchecker.impl.LinkCheckerImpl",
            "com.adobe.granite.contexthub.impl.ContextHubImpl",
            "com.day.cq.dam.core.impl.DamEventRecorderImpl",
            "com.day.cq.replication.impl.ReplicationSettingsImpl",
            "com.day.cq.replication.impl.agent.AgentManagerImpl",
            "com.adobe.granite.workflow.purge.Scheduler",
            "com.adobe.granite.auth.saml.SamlAuthenticationHandler",
            "com.adobe.granite.auth.oauth.AccessTokenProvider",
            "com.adobe.cq.mcm.landingpage.LandingPageServlet",
            "com.adobe.granite.taxonomy.impl.TaxonomyServiceImpl",
            "com.adobe.granite.license.impl.LicenseServiceImpl",
            "com.adobe.acs.commons.marketing.variants.impl.MktVariantsImpl",
            "com.adobe.cq.upgradedeployer.Upgrader",
            "com.day.cq.mailer.DefaultMailService",
            "com.day.cq.mailing.impl.MailingServiceImpl",
            "com.adobe.granite.workflow.core.email.WorkflowEmailNotificationService",
            "com.day.cq.workflow.impl.email.EMailNotificationService",
            "com.day.cq.rewriter.linkchecker.impl.LinkCheckerTransformerFactory",
            "com.day.cq.rewriter.linkchecker.impl.LinkCheckerTask",
            // Apache Sling Event Jobs
            "org.apache.sling.event.jobs.QueueConfiguration",
            // Community extensions
            "biz.netcentric.cq.tools.actool.impl.AcInstallationServiceImpl",
            "be.orbinson.aem.groovy.console.configuration.impl.DefaultConfigurationService",
            "io.wcm.caconfig.extensions.persistence.impl.PagePersistenceStrategy",
            // OSGi framework
            "org.apache.felix.http.jetty.internal.JettyConfig",
            "org.apache.felix.webconsole.internal.OsgiManagerPlugin",
            "org.apache.felix.eventadmin.impl.EventAdmin",
            "org.apache.felix.scr.impl.ScrComponentFactory",
            "org.apache.felix.configadmin.ConfigurationAdmin",
            "org.apache.felix.fileinstall.internal.FileInstall");

    private static final List<String> WILDCARD_PREFIXES = List.of(
            "com.adobe.cq.wcm.core.components.",
            "com.adobe.acs.commons.",
            "com.adobe.granite.cors.",
            "com.adobe.granite.workflow.",
            "com.day.cq.workflow.",
            "com.day.cq.rewriter.",
            "io.wcm.",
            "org.apache.sling.sitemap.",
            "org.apache.sling.repoinit.",
            "org.apache.sling.i18n.",
            "org.apache.sling.caconfig.",
            "org.apache.sling.jcr.davex.",
            "org.apache.sling.xss.",
            "com.adobe.cq.dam.cqi.",
            "com.day.cq.dam.core.process.",
            "com.day.cq.wcm.core.stats.",
            "com.day.cq.dam.commons.util.",
            "com.adobe.dam.fontmanager.impl.",
            "com.day.cq.workflow.launcher.impl.WorkflowLauncher",
            "org.apache.jackrabbit.oak.plugins.index.",
            "com.adobe.aem",
            "com.day.cq.replication.impl.transport.",
            "org.apache.sling.distribution.monitor.",
            "org.apache.sling.event.impl.",
            "org.apache.sling.event.jobs.");

    private PlatformPidCatalog() {
    }

    public static boolean isPlatformPid(String pid) {
        if (pid == null || pid.isBlank()) {
            return false;
        }
        if (EXACT_PIDS.contains(pid)) {
            return true;
        }
        if (WILDCARD_PREFIXES.stream().anyMatch(prefix -> matchesPrefix(pid, prefix))) {
            return true;
        }
        // Factory-instance naming. Adobe ships factory configurations as
        // "<factoryPid>-<instance>" (e.g. ServiceUserMapperImpl.amended-<project>) as well as the
        // RFC 724 "<factoryPid>~<instance>" form. The project never declares a platform component, so
        // a suffixed platform PID can never be validated by component matching and must not be
        // reported as orphaned project code. A genuine duplicate would use the exact PID, which is
        // still matched above.
        int dash = pid.lastIndexOf('-');
        if (dash > 0 && EXACT_PIDS.contains(pid.substring(0, dash))) {
            return true;
        }
        int tilde = pid.lastIndexOf('~');
        return tilde > 0 && EXACT_PIDS.contains(pid.substring(0, tilde));
    }

    /**
     * Segment-boundary prefix match: {@code com.adobe.aem} must not match
     * {@code com.adobe.aemfoo.MyPid}. A prefix that already ends with a dot keeps its
     * package semantics; a class-level prefix additionally matches its exact PID.
     */
    private static boolean matchesPrefix(String pid, String prefix) {
        if (prefix.endsWith(".")) {
            return pid.startsWith(prefix);
        }
        return pid.equals(prefix) || pid.startsWith(prefix + ".");
    }

    
    
    }
