package io.github.rohithv16.aem.analyser.core.api;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves service-user analysis works through standard Sling/JCR APIs only, with no
 * project-specific identifiers or sample-repo assumptions.
 */
class OotbServiceUserDetectionTest {

    @TempDir
    Path root;

    private void write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void detectsUnmappedAndIgnoresMappedOotbResolverApis() throws Exception {
        write("pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules><module>core</module><module>ui.config</module></modules>
                </project>
                """);
        write("core/pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0.0</version></parent>
                  <artifactId>core</artifactId>
                  <packaging>jar</packaging>
                  <build><plugins><plugin>
                    <groupId>org.apache.felix</groupId><artifactId>maven-bundle-plugin</artifactId>
                  </plugin></plugins></build>
                </project>
                """);
        write("ui.config/pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0.0</version></parent>
                  <artifactId>ui.config</artifactId>
                  <packaging>content-package</packaging>
                  <build><plugins><plugin>
                    <groupId>org.apache.jackrabbit</groupId><artifactId>filevault-package-maven-plugin</artifactId>
                    <configuration><packageType>container</packageType></configuration>
                  </plugin></plugins></build>
                </project>
                """);

        write("core/src/main/java/com/example/ResolverConsumer.java", """
                package com.example;
                import java.util.Map;
                import org.apache.sling.api.resource.ResourceResolver;
                import org.apache.sling.api.resource.ResourceResolverFactory;
                public class ResolverConsumer {
                    private ResourceResolverFactory factory;
                    ResourceResolver acquire() throws Exception {
                        return factory.getResourceResolver(
                                Map.of(ResourceResolverFactory.SUBSERVICE, "unmapped-service"));
                    }
                }
                """);
        write("core/src/main/java/com/example/LoginConsumer.java", """
                package com.example;
                import javax.jcr.Session;
                import org.apache.sling.jcr.api.SlingRepository;
                public class LoginConsumer {
                    private SlingRepository repository;
                    Session login() throws Exception {
                        return repository.loginService("mapped-service", null);
                    }
                }
                """);

        String configRoot = "ui.config/src/main/content/jcr_root/apps/sample/osgiconfig/config/";
        write(configRoot + "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~app.cfg.json",
                """
                        {
                          "user.mapping": [
                            "com.example.core:mapped-service=[example-service-user]"
                          ]
                        }
                        """);
        write(configRoot + "org.apache.sling.jcr.repoinit.RepositoryInitializer~users.cfg.json",
                """
                        {
                          "scripts": ["create service user example-service-user"]
                        }
                        """);

        AnalysisReport report = new DefaultAemCodeAnalyser().analyze(AnalysisContext.of(root));
        List<Violation> cloud003 = report.violations().stream()
                .filter(v -> RuleId.AEM_CLOUD_003.id().equals(v.ruleId()))
                .toList();

        assertEquals(1, cloud003.size(), () -> "Expected exactly one unmapped OOTB call: " + cloud003);
        assertTrue(cloud003.get(0).message().contains("unmapped-service"), cloud003.get(0).message());
        assertTrue(cloud003.stream().noneMatch(v -> v.filePath().contains("LoginConsumer")),
                () -> "Mapped OOTB repository login must not be reported: " + cloud003);
    }
}
