package io.github.aemtools.analyser.core.rules;

import io.github.aemtools.analyser.core.api.AnalysisContext;
import io.github.aemtools.analyser.core.api.AemCodeAnalyser;
import io.github.aemtools.analyser.core.api.DefaultAemCodeAnalyser;
import io.github.aemtools.analyser.core.model.report.AnalysisReport;
import io.github.aemtools.analyser.core.model.report.RuleId;
import io.github.aemtools.analyser.core.model.report.Severity;
import io.github.aemtools.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden end-to-end fixture modeled after the real-world production defects
 * (Case Study 1: PID-mismatched servlet config; Case Study 2: OCD-named orphaned config)
 * plus false-positive elimination checks (whiteboard servlet, whitelisted property,
 * underscore metatype property, factory config).
 */
class EndToEndAnalysisTest {

    @TempDir
    Path projectRoot;

    private Path write(String relativePath, String content) throws Exception {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private void writeFixtureProject() throws Exception {
        // Root aggregator POM
        write("pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>example-aem</artifactId>
                  <version>1.0.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                    <module>ui.config</module>
                  </modules>
                </project>
                """);

        // Bundle module POM
        write("core/pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>example-aem</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                  </parent>
                  <artifactId>example-core</artifactId>
                  <packaging>jar</packaging>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.felix</groupId>
                        <artifactId>maven-bundle-plugin</artifactId>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        // ui.config module POM (content-package with packageType container, no embeddeds)
        write("ui.config/pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>example-aem</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                  </parent>
                  <artifactId>example-ui.config</artifactId>
                  <packaging>content-package</packaging>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.jackrabbit</groupId>
                        <artifactId>filevault-package-maven-plugin</artifactId>
                        <configuration>
                          <packageType>container</packageType>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        // ── Case Study 2: FlourishCmpConfig (OCD-named orphaned config) ──
        write("core/src/main/java/com/example/core/services/FlourishCmpService.java", """
                package com.example.core.services;
                public interface FlourishCmpService {
                    String doWork();
                }
                """);
        write("core/src/main/java/com/example/core/config/FlourishCmpConfig.java", """
                package com.example.core.config;
                import org.osgi.service.metatype.annotations.ObjectClassDefinition;
                @ObjectClassDefinition(name = "Flourish Configuration")
                public @interface FlourishCmpConfig {
                    boolean enabled() default true;
                    String endpointUrl() default "https://flourish.example.com";
                    String obsoleteProp() default "unused";
                }
                """);
        write("core/src/main/java/com/example/core/services/impl/FlourishCmpServiceImpl.java", """
                package com.example.core.services.impl;
                import com.example.core.config.FlourishCmpConfig;
                import com.example.core.services.FlourishCmpService;
                import org.osgi.service.component.annotations.Component;
                import org.osgi.service.component.annotations.Designate;
                @Designate(ocd = FlourishCmpConfig.class)
                @Component(service = FlourishCmpService.class, immediate = true)
                public class FlourishCmpServiceImpl implements FlourishCmpService {
                    private FlourishCmpConfig config;
                    @Override
                    public String doWork() {
                        boolean active = config.enabled();
                        String url = config.endpointUrl();
                        return url + ":" + active;
                    }
                }
                """);

        // ── Case Study 1: ContentSyncReplicationServletConfig (PID mismatch, shared OCD) ──
        write("core/src/main/java/com/example/core/config/contentsync/ContentSyncReplicationServletConfig.java", """
                package com.example.core.config.contentsync;
                import org.osgi.service.metatype.annotations.AttributeDefinition;
                import org.osgi.service.metatype.annotations.ObjectClassDefinition;
                @ObjectClassDefinition(name = "Content Sync Replication Servlet Configuration")
                public @interface ContentSyncReplicationServletConfig {
                    @AttributeDefinition(name = "Enabled", description = "Whether servlets are active")
                    boolean enabled() default false;
                    @AttributeDefinition int bulkReplicationBatchSize() default 200;
                    @AttributeDefinition int trackCommitBatchSize() default 200;
                    @AttributeDefinition int trackCommitPauseMs() default 0;
                }
                """);
        write("core/src/main/java/com/example/core/servlets/contentsync/ContentSyncBulkReplicationServlet.java", """
                package com.example.core.servlets.contentsync;
                import com.example.core.config.contentsync.ContentSyncReplicationServletConfig;
                import org.osgi.service.component.annotations.Component;
                import org.osgi.service.component.annotations.Designate;
                @Designate(ocd = ContentSyncReplicationServletConfig.class)
                @Component(service = javax.servlet.Servlet.class, immediate = true)
                public class ContentSyncBulkReplicationServlet implements javax.servlet.Servlet {
                    private ContentSyncReplicationServletConfig config;
                    public void process() {
                        int batchSize = config.bulkReplicationBatchSize();
                        int commitBatch = config.trackCommitBatchSize();
                    }
                }
                """);
        write("core/src/main/java/com/example/core/servlets/contentsync/ContentSyncTrackActivityServlet.java", """
                package com.example.core.servlets.contentsync;
                import com.example.core.config.contentsync.ContentSyncReplicationServletConfig;
                import org.osgi.service.component.annotations.Component;
                import org.osgi.service.component.annotations.Designate;
                @Designate(ocd = ContentSyncReplicationServletConfig.class)
                @Component(service = javax.servlet.Servlet.class, immediate = true)
                public class ContentSyncTrackActivityServlet implements javax.servlet.Servlet {
                    private ContentSyncReplicationServletConfig config;
                    public void process() {
                        int pauseMs = config.trackCommitPauseMs();
                        boolean on = config.enabled();
                    }
                }
                """);

        // ── False positive eliminations ──
        // Whiteboard servlet with underscore metatype property and factory config
        write("core/src/main/java/com/example/core/servlets/StyleIndexingProfileServlet.java", """
                package com.example.core.servlets;
                import org.osgi.service.component.annotations.Component;
                import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
                import org.osgi.service.metatype.annotations.AttributeDefinition;
                import org.osgi.service.metatype.annotations.ObjectClassDefinition;
                @Component(service = javax.servlet.Servlet.class, property = "sling.servlet.paths=/bin/styleprofile")
                public class StyleIndexingProfileServlet extends SlingSafeMethodsServlet {
                }
                """);

        // Unused service (real dead code → AEM-DEAD-003A)
        write("core/src/main/java/com/example/core/services/DeadService.java", """
                package com.example.core.services;
                public interface DeadService {
                    void execute();
                }
                """);
        write("core/src/main/java/com/example/core/services/impl/DeadServiceImpl.java", """
                package com.example.core.services.impl;
                import com.example.core.services.DeadService;
                import org.osgi.service.component.annotations.Component;
                @Component(service = DeadService.class, immediate = true)
                public class DeadServiceImpl implements DeadService {
                    @Override
                    public void execute() {
                    }
                }
                """);

        // Unused Sling Model without resourceType (AEM-DEAD-003B INFO)
        write("core/src/main/java/com/example/core/models/DeadModel.java", """
                package com.example.core.models;
                import org.apache.sling.api.SlingHttpServletRequest;
                import org.apache.sling.models.annotations.Model;
                @Model(adaptables = SlingHttpServletRequest.class)
                public class DeadModel {
                }
                """);

        // ── ui.config configuration files ──
        String configRoot = "ui.config/src/main/content/jcr_root/apps/example/osgiconfig/";
        // Case Study 1: orphaned config named after the OCD class, not the servlet PIDs
        write(configRoot + "config.author/com.example.core.config.contentsync.ContentSyncReplicationServletConfig.cfg.json",
                """
                        {
                          "enabled": true,
                          "bulkReplicationBatchSize": 200,
                          "trackCommitBatchSize": 50,
                          "trackCommitPauseMs": 150
                        }
                        """);
        // Case Study 2: orphaned config named after the OCD interface
        write(configRoot + "config/com.example.core.config.FlourishCmpConfig.cfg.json",
                """
                        {
                          "enabled": true,
                          "endpointUrl": "https://prod.example.com"
                        }
                        """);
        // Correctly bound config with one ghost property and one whitelisted property
        write(configRoot + "config/com.example.core.services.impl.FlourishCmpServiceImpl.cfg.json",
                """
                        {
                          "enabled:Integer": 1,
                          "endpointUrl": "https://flourish.example.com",
                          "obsoleteEndpoint": "https://old.example.com",
                          "webconsole_configurationFactory_nameHint": "{name} config"
                        }
                        """);
        // Correctly bound scheduler config using an RFC 724 typed key that must match
        // the OCD attribute method scheduledpurge_daysold() via metatype normalization
        write(configRoot + "config/com.example.core.schedulers.WorkflowPurgeScheduler.cfg.json",
                """
                        {
                          "scheduledpurge.daysold:Integer": 30
                        }
                        """);
        // Platform config: must NOT be flagged
        write(configRoot + "config/org.apache.sling.jcr.repoinit.RepositoryInitializer.cfg.json",
                """
                        {
                          "namespace": "sample"
                        }
                        """);
        // Factory configuration (tilde): must NOT be flagged when the component exists
        write(configRoot + "config/com.example.core.services.impl.FactoryServiceImpl~tenant-a.cfg.json",
                """
                        {
                          "prop": "value"
                        }
                        """);
        // Tooling hazard file at module root: must be ignored entirely
        write("ui.config/lombok.config", "lombok.log.fieldName=LOG\n");
        // Sling service interface for factory component
        write("core/src/main/java/com/example/core/services/FactoryService.java", """
                package com.example.core.services;
                public interface FactoryService {
                    String tenant();
                }
                """);
        write("core/src/main/java/com/example/core/services/impl/FactoryServiceImpl.java", """
                package com.example.core.services.impl;
                import com.example.core.services.FactoryService;
                import org.osgi.service.component.annotations.Component;
                import org.osgi.service.component.annotations.Designate;
                import org.osgi.service.component.annotations.ConfigurationPolicy;
                import org.osgi.service.metatype.annotations.AttributeDefinition;
                import org.osgi.service.metatype.annotations.ObjectClassDefinition;
                @Designate(ocd = FactoryServiceImpl.TenantConfig.class, factory = true)
                @Component(service = FactoryService.class, configurationPolicy = ConfigurationPolicy.REQUIRE)
                public class FactoryServiceImpl implements FactoryService {
                    @ObjectClassDefinition(name = "Tenant")
                    @interface TenantConfig {
                        @AttributeDefinition String prop() default "x";
                    }
                    private TenantConfig config;
                    @Override
                    public String tenant() {
                        return config.prop();
                    }
                }
                """);
        // Consumers that inject the live services — proves AEM-DEAD-003A only fires on truly unused services
        write("core/src/main/java/com/example/core/services/impl/LiveServiceConsumer.java", """
                package com.example.core.services.impl;
                import com.example.core.services.FactoryService;
                import com.example.core.services.FlourishCmpService;
                import org.osgi.service.component.annotations.Component;
                import org.osgi.service.component.annotations.Reference;
                @Component(immediate = true)
                public class LiveServiceConsumer {
                    @Reference
                    private FlourishCmpService flourishCmpService;
                    @Reference
                    private FactoryService factoryService;
                }
                """);

        // Scheduler consumed via metatype-normalized typed key (framework-invoked, never flagged)
        write("core/src/main/java/com/example/core/schedulers/WorkflowPurgeScheduler.java", """
                package com.example.core.schedulers;
                import org.osgi.service.component.annotations.Component;
                import org.osgi.service.component.annotations.Designate;
                import org.osgi.service.metatype.annotations.AttributeDefinition;
                import org.osgi.service.metatype.annotations.ObjectClassDefinition;
                @Designate(ocd = WorkflowPurgeScheduler.Config.class)
                @Component(service = Runnable.class, immediate = true)
                public class WorkflowPurgeScheduler implements Runnable {
                    @ObjectClassDefinition(name = "Workflow Purge")
                    @interface Config {
                        @AttributeDefinition(description = "Days before purge")
                        Long scheduledpurge_daysold() default 30L;
                    }
                    private Config config;
                    @Override
                    public void run() {
                        long days = config.scheduledpurge_daysold();
                        System.out.println(days);
                    }
                }
                """);
    }

    @Test
    void detectsBothProductionDefectsWithZeroFalsePositives() throws Exception {
        writeFixtureProject();
        AemCodeAnalyser analyser = new DefaultAemCodeAnalyser();
        AnalysisReport report = analyser.analyze(AnalysisContext.of(projectRoot));

        Map<String, List<Violation>> byRule = report.violations().stream()
                .collect(Collectors.groupingBy(Violation::ruleId));

        // AEM-DEAD-001: both real defects detected
        List<Violation> orphans = byRule.getOrDefault(RuleId.AEM_DEAD_001.id(), List.of());
        assertEquals(2, orphans.size(), () -> "Orphaned configs flagged: "
                + orphans.stream().map(v -> v.filePath() + " → " + v.subtype()).toList());
        assertTrue(orphans.stream().allMatch(v -> v.subtype().equals("ORPHANED_OCD_NAMED_CONFIG")),
                () -> "Subtypes: " + orphans.stream().map(v -> v.subtype() + " @ " + v.filePath()).toList());

        // AEM-DEAD-002A: exactly one ghost property
        List<Violation> ghosts = byRule.getOrDefault(RuleId.AEM_DEAD_002A.id(), List.of());
        assertEquals(1, ghosts.size());
        assertEquals("obsoleteEndpoint", extractKey(ghosts.get(0).message()));

        // AEM-DEAD-002B: exactly one unconsumed OCD property (obsoleteProp)
        List<Violation> unconsumed = byRule.getOrDefault(RuleId.AEM_DEAD_002B.id(), List.of());
        assertEquals(1, unconsumed.size(), () -> "Unconsumed OCD properties flagged: "
                + unconsumed.stream().map(Violation::message).toList());
        assertTrue(unconsumed.get(0).message().contains("obsoleteProp"));

        // AEM-DEAD-003A: exactly one unused service implementation (DeadServiceImpl)
        List<Violation> unusedServices = byRule.getOrDefault(RuleId.AEM_DEAD_003A.id(), List.of());
        assertEquals(1, unusedServices.size(), () -> "Unused services flagged: "
                + unusedServices.stream().map(v -> v.filePath()).toList());
        assertTrue(unusedServices.get(0).filePath().contains("DeadServiceImpl"));

        // AEM-DEAD-003B: unused Sling Model flagged at INFO severity
        List<Violation> unusedModels = byRule.getOrDefault(RuleId.AEM_DEAD_003B.id(), List.of());
        assertEquals(1, unusedModels.size());
        assertEquals(Severity.INFO, unusedModels.get(0).severity());
        assertTrue(unusedModels.get(0).filePath().contains("DeadModel"));

        // ── Zero false positives ──
        List<Violation> allViolations = report.violations();

        // RepositoryInitializer platform config is never flagged
        assertFalse(allViolations.stream().anyMatch(v -> v.filePath() != null
                        && v.filePath().contains("RepositoryInitializer")),
                () -> "Platform config falsely flagged: " + allViolations);

        // Factory config for a live component (~tenant-a) is never flagged
        assertFalse(allViolations.stream().anyMatch(v -> v.filePath() != null
                        && v.filePath().contains("FactoryServiceImpl~tenant-a")),
                () -> "Factory config falsely flagged: " + allViolations);

        // Whiteboard servlet is never flagged as unused service
        assertFalse(allViolations.stream().anyMatch(v -> v.filePath() != null
                        && v.filePath().contains("StyleIndexingProfileServlet")),
                () -> "Whiteboard servlet falsely flagged: " + allViolations);

        // webconsole_configurationFactory_nameHint is whitelisted as framework property
        assertFalse(allViolations.stream().anyMatch(v -> v.message() != null
                        && v.message().contains("webconsole_configurationFactory_nameHint")),
                () -> "Whitelisted property falsely flagged: " + allViolations);

        // scheduledpurge.daysold:Integer typed key binds to the scheduledpurge.daysold OCD property
        assertFalse(allViolations.stream().anyMatch(v -> v.message() != null
                        && v.message().contains("scheduledpurge")),
                () -> "Typed key falsely flagged: " + allViolations);
    }

    private String extractKey(String message) {
        int start = message.indexOf('\'') + 1;
        int end = message.indexOf('\'', start);
        return message.substring(start, end);
    }
}
