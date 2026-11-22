package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleClassification;
import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud009Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AEM-CLOUD-009 against the cases from the issue: a module added to the reactor but not to the
 * container (one warning naming it), a bundle embedded outside {@code /install} (one error), and
 * the shapes that must stay silent — the portfolio's embed graph with a structure package reached
 * through {@code <repositoryStructurePackages>}, a bundle embedded under {@code .../install}, a
 * module that carries {@code cloudManagerTarget=none}, and a reactor without a container.
 */
class AemCloud009RuleTest {

    @TempDir
    Path root;

    @Test
    void moduleMissingFromTheContainerIsReportedOnce() throws Exception {
        ModuleDescriptor all = module("acme.all", ModuleClassification.CONTAINER_MODULE, "content-package",
                """
                        <configuration>
                          <packageType>container</packageType>
                          <embeddeds>
                            <embedded>
                              <groupId>com.acme</groupId>
                              <artifactId>acme.ui.apps</artifactId>
                              <type>zip</type>
                              <target>/apps/acme-packages/application/install</target>
                            </embedded>
                          </embeddeds>
                        </configuration>
                        """);
        ModuleDescriptor apps = module("acme.ui.apps", ModuleClassification.APPS_MODULE, "content-package",
                plugin("container"));
        ModuleDescriptor content = module("acme.ui.content2", ModuleClassification.CONTENT_MODULE,
                "content-package", plugin("content"));

        List<Violation> violations = evaluate(all, apps, content);

        assertEquals(1, violations.size(), () -> violations.toString());
        Violation violation = violations.get(0);
        assertEquals("UNREACHABLE_MODULE", violation.subtype());
        assertEquals(Severity.WARNING, violation.severity());
        assertTrue(violation.message().contains("acme.ui.content2"), violation.message());
        assertTrue(violation.filePath().endsWith("acme.ui.content2/pom.xml"), violation.filePath());
        assertEquals(1, violation.relatedLocations().size(), "the container it should be embedded in");
    }

    @Test
    void bundleEmbeddedOutsideAnInstallPathIsAnError() throws Exception {
        ModuleDescriptor all = module("acme.all", ModuleClassification.CONTAINER_MODULE, "content-package",
                """
                        <configuration>
                          <packageType>container</packageType>
                          <embeddeds>
                            <embedded>
                              <groupId>com.acme</groupId>
                              <artifactId>acme.core</artifactId>
                              <target>/apps/acme-packages/application</target>
                            </embedded>
                          </embeddeds>
                        </configuration>
                        """);
        ModuleDescriptor core = module("acme.core", ModuleClassification.BUNDLE_MODULE, "jar", plugin("bundle"));

        List<Violation> violations = evaluate(all, core);

        assertEquals(1, violations.size(), () -> violations.toString());
        assertEquals("BUNDLE_EMBEDDED_WITHOUT_INSTALL", violations.get(0).subtype());
        assertEquals(Severity.ERROR, violations.get(0).severity());
        assertTrue(violations.get(0).message().contains("/apps/acme-packages/application"));
    }

    @Test
    void portfolioEmbedGraphIsReachableAndSilent() throws Exception {
        // The portfolio's shape: all embeds apps/content/config/core, ui.apps reaches its structure
        // package through <repositoryStructurePackages>, and the structure package opts out.
        ModuleDescriptor all = module("acme.all", ModuleClassification.CONTAINER_MODULE, "content-package",
                """
                        <configuration>
                          <packageType>container</packageType>
                          <embeddeds>
                            <embedded>
                              <groupId>com.acme</groupId>
                              <artifactId>acme.ui.apps</artifactId>
                              <type>zip</type>
                              <target>/apps/acme-packages/application/install</target>
                            </embedded>
                            <embedded>
                              <groupId>com.acme</groupId>
                              <artifactId>acme.core</artifactId>
                              <target>/apps/acme-packages/application/install</target>
                            </embedded>
                            <embedded>
                              <groupId>com.acme</groupId>
                              <artifactId>acme.ui.content</artifactId>
                              <type>zip</type>
                              <target>/apps/acme-packages/content/install</target>
                            </embedded>
                            <embedded>
                              <groupId>com.acme</groupId>
                              <artifactId>acme.ui.config</artifactId>
                              <type>zip</type>
                              <target>/apps/acme-packages/application/install</target>
                            </embedded>
                          </embeddeds>
                        </configuration>
                        """);
        ModuleDescriptor apps = module("acme.ui.apps", ModuleClassification.APPS_MODULE, "content-package",
                """
                        <configuration>
                          <packageType>container</packageType>
                          <repositoryStructurePackages>
                            <repositoryStructurePackage>
                              <groupId>com.acme</groupId>
                              <artifactId>acme.ui.apps.structure</artifactId>
                            </repositoryStructurePackage>
                          </repositoryStructurePackages>
                        </configuration>
                        """);
        ModuleDescriptor structure = module("acme.ui.apps.structure", ModuleClassification.STRUCTURE_MODULE,
                "content-package",
                """
                        <configuration>
                          <packageType>container</packageType>
                          <properties>
                            <cloudManagerTarget>none</cloudManagerTarget>
                          </properties>
                        </configuration>
                        """);
        ModuleDescriptor content = module("acme.ui.content", ModuleClassification.CONTENT_MODULE,
                "content-package", plugin("content"));
        ModuleDescriptor config = module("acme.ui.config", ModuleClassification.CONFIG_MODULE,
                "content-package", plugin("container"));
        ModuleDescriptor core = module("acme.core", ModuleClassification.BUNDLE_MODULE, "jar", plugin("bundle"));

        assertTrue(evaluate(all, apps, structure, content, config, core).isEmpty(),
                "Every deployable module is reachable; this is the portfolio's real graph");
    }

    @Test
    void structurePackageReachedThroughTheContainerIsSilent() throws Exception {
        ModuleDescriptor all = module("acme.all", ModuleClassification.CONTAINER_MODULE, "content-package",
                """
                        <configuration>
                          <embeddeds>
                            <embedded>
                              <groupId>com.acme</groupId>
                              <artifactId>acme.ui.config</artifactId>
                              <type>zip</type>
                              <target>/apps/acme-packages/application/install</target>
                            </embedded>
                          </embeddeds>
                          <repositoryStructurePackages>
                            <repositoryStructurePackage>
                              <artifactId>acme.ui.apps.structure</artifactId>
                            </repositoryStructurePackage>
                          </repositoryStructurePackages>
                        </configuration>
                        """);
        ModuleDescriptor structure = module("acme.ui.apps.structure", ModuleClassification.STRUCTURE_MODULE,
                "content-package", plugin("container"));
        ModuleDescriptor config = module("acme.ui.config", ModuleClassification.CONFIG_MODULE,
                "content-package", plugin("container"));

        assertTrue(evaluate(all, structure, config).isEmpty());
    }

    @Test
    void containerCarryingItsOwnContentIsAnError() throws Exception {
        ModuleDescriptor all = module("acme.all", ModuleClassification.CONTAINER_MODULE, "content-package",
                plugin("container"));
        Path content = root.resolve("acme.all/src/main/content/jcr_root/apps/acme/.content.xml");
        Files.createDirectories(content.getParent());
        Files.writeString(content, "<jcr:root/>");

        List<Violation> violations = evaluate(all);

        assertEquals(1, violations.size(), () -> violations.toString());
        assertEquals("CONTAINER_SHIPPING_CONTENT", violations.get(0).subtype());
        assertEquals(Severity.ERROR, violations.get(0).severity());
    }

    @Test
    void containerWithoutAJcrRootStaysSilent() throws Exception {
        ModuleDescriptor all = module("acme.all", ModuleClassification.CONTAINER_MODULE, "content-package",
                plugin("container"));
        Path metaInf = root.resolve("acme.all/src/main/content/META-INF/vault/filter.xml");
        Files.createDirectories(metaInf.getParent());
        Files.writeString(metaInf, "<workspaceFilter/>");

        assertTrue(evaluate(all).isEmpty(), "A container with only META-INF/vault ships no content");
    }

    @Test
    void reactorWithoutAContainerIsNotJudged() throws Exception {
        ModuleDescriptor apps = module("acme.ui.apps", ModuleClassification.APPS_MODULE, "content-package",
                plugin("container"));
        ModuleDescriptor content = module("acme.ui.content", ModuleClassification.CONTENT_MODULE,
                "content-package", plugin("content"));

        assertTrue(evaluate(apps, content).isEmpty(),
                "A single-package project has no container to be unreachable from");
    }

    private ModuleDescriptor module(String artifactId, ModuleClassification classification,
                                    String packaging, String pluginConfiguration) throws Exception {
        Path directory = root.resolve(artifactId);
        Files.createDirectories(directory);
        Path pom = directory.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>acme.parent</artifactId><version>1.0.0</version></parent>
                  <artifactId>%s</artifactId>
                  <packaging>%s</packaging>
                  <build><plugins><plugin>
                    <groupId>org.apache.jackrabbit</groupId>
                    <artifactId>filevault-package-maven-plugin</artifactId>
                %s
                  </plugin></plugins></build>
                </project>
                """.formatted(artifactId, packaging, pluginConfiguration));
        return new ModuleDescriptor(artifactId, "com.acme", packaging, directory, pom, classification,
                "acme.parent", false, Set.of(), Set.of());
    }

    private String plugin(String packageType) {
        return """
                        <configuration><packageType>%s</packageType></configuration>
                """.formatted(packageType);
    }

    private List<Violation> evaluate(ModuleDescriptor... modules) {
        ReferenceGraph graph = new ReferenceGraph();
        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), List.of(), List.of(), null);
        RuleContext context = new RuleContext(graph, buildResult, Collections.emptySet(), null,
                Collections.emptySet(), root, List.of(), List.of(), List.of(),
                new ArrayList<>(List.of(modules)));
        return new AemCloud009Rule().evaluate(context);
    }
}
