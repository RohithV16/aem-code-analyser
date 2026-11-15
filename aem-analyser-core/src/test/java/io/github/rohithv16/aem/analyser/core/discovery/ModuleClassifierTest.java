package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleClassification;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModuleClassifierTest {

    private static final Path DIR = Path.of("/tmp/unused");

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    private ModuleClassifier.ClassificationInput input(String artifactId, String packaging, boolean hasModules,
                                                       FileVaultPluginConfig fvConfig, boolean hasOsgiConfigs,
                                                       boolean hasBndPlugin) {
        return new ModuleClassifier.ClassificationInput(artifactId, packaging, DIR, hasModules,
                fvConfig, hasOsgiConfigs, hasBndPlugin, false, false);
    }

    @Test
    void aggregatorPomWithModules() {
        assertEquals(ModuleClassification.AGGREGATOR_MODULE,
                new ModuleClassifier().classify(input("my-project", "pom", true,
                        FileVaultPluginConfig.fromPluginConfiguration("my-project", null), false, false)));
    }

    @Test
    void containerWithEmbeddeds() {
        FileVaultPluginConfig config = FileVaultPluginConfig.fromPluginConfiguration("myapp.all",
                newEmbeddedsConfig());
        assertEquals(ModuleClassification.CONTAINER_MODULE,
                new ModuleClassifier().classify(input("myapp.all", "content-package", false, config, false, false)));
    }

    @Test
    void uiConfigWithOsgiConfigs() {
        FileVaultPluginConfig config = FileVaultPluginConfig.fromPluginConfiguration("myapp.ui.config", null);
        assertEquals(ModuleClassification.CONFIG_MODULE,
                new ModuleClassifier().classify(input("myapp.ui.config", "content-package", false, config, true, false)));
    }

    @Test
    void uiApps() {
        FileVaultPluginConfig config = FileVaultPluginConfig.fromPluginConfiguration("myapp.ui.apps", null);
        assertEquals(ModuleClassification.APPS_MODULE,
                new ModuleClassifier().classify(input("myapp.ui.apps", "content-package", false, config, false, false)));
    }

    @Test
    void uiContentAndStructureAndAcl() {
        FileVaultPluginConfig config = FileVaultPluginConfig.fromPluginConfiguration("myapp.ui.content", null);
        assertEquals(ModuleClassification.CONTENT_MODULE,
                new ModuleClassifier().classify(input("myapp.ui.content", "content-package", false, config, false, false)));
        assertEquals(ModuleClassification.STRUCTURE_MODULE,
                new ModuleClassifier().classify(input("myapp.ui.apps.structure", "content-package", false,
                        FileVaultPluginConfig.fromPluginConfiguration("myapp.ui.apps.structure", null), false, false)));
        assertEquals(ModuleClassification.ACL_MODULE,
                new ModuleClassifier().classify(input("myapp.ui.acl", "content-package", false,
                        FileVaultPluginConfig.fromPluginConfiguration("myapp.ui.acl", null), false, false)));
    }

    @Test
    void bundleModule() {
        assertEquals(ModuleClassification.BUNDLE_MODULE,
                new ModuleClassifier().classify(input("myapp.core", "jar", false,
                        FileVaultPluginConfig.fromPluginConfiguration("myapp.core", null), false, true)));
        assertEquals(ModuleClassification.BUNDLE_MODULE,
                new ModuleClassifier().classify(input("myapp.core", "bundle", false,
                        FileVaultPluginConfig.fromPluginConfiguration("myapp.core", null), false, false)));
    }

    @Test
    void testModule() {
        assertEquals(ModuleClassification.TEST_MODULE,
                new ModuleClassifier().classify(input("myapp.it.tests", "jar", false,
                        FileVaultPluginConfig.fromPluginConfiguration("myapp.it.tests", null), false, false)));
    }

    @Test
    void frontendModuleByPackageJson() throws Exception {
        Path frontendDir = tempDir.resolve("myapp.ui.frontend");
        java.nio.file.Files.createDirectories(frontendDir);
        java.nio.file.Files.writeString(frontendDir.resolve("package.json"), "{}");
        assertEquals(ModuleClassification.FRONTEND_MODULE,
                new ModuleClassifier().classify(new ModuleClassifier.ClassificationInput(
                        "myapp.ui.frontend", "jar", frontendDir, false,
                        FileVaultPluginConfig.fromPluginConfiguration("myapp.ui.frontend", null),
                        false, false, false, false)));
    }

    @Test
    void dispatcherAndPipeline() {
        assertEquals(ModuleClassification.DISPATCHER_MODULE,
                new ModuleClassifier().classify(input("myapp.dispatcher", "pom", false,
                        FileVaultPluginConfig.fromPluginConfiguration("myapp.dispatcher", null), false, false)));
        assertEquals(ModuleClassification.PIPELINE_MODULE,
                new ModuleClassifier().classify(input("pipelines", "pom", false,
                        FileVaultPluginConfig.fromPluginConfiguration("pipelines", null), false, false)));
    }

    private Object newEmbeddedsConfig() {
        // builds an Xpp3Dom with an <embeddeds> child via the actual plugin config parser
        org.codehaus.plexus.util.xml.Xpp3Dom dom = new org.codehaus.plexus.util.xml.Xpp3Dom("configuration");
        org.codehaus.plexus.util.xml.Xpp3Dom embeddeds = new org.codehaus.plexus.util.xml.Xpp3Dom("embeddeds");
        org.codehaus.plexus.util.xml.Xpp3Dom embedded = new org.codehaus.plexus.util.xml.Xpp3Dom("embedded");
        org.codehaus.plexus.util.xml.Xpp3Dom target = new org.codehaus.plexus.util.xml.Xpp3Dom("target");
        target.setValue("/apps/myapp/install");
        embedded.addChild(target);
        embeddeds.addChild(embedded);
        dom.addChild(embeddeds);
        return dom;
    }
}
