package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.ConfigFormat;
import io.github.rohithv16.aem.analyser.core.model.graph.DataType;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.ConfigPropertyNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.htl.HtlUnsafeContextFinding;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud002Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud003Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemSec001Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AemCloudAndSecRulesTest {

    private final Path root = Path.of("/mock/project");

    @Test
    void testAemCloud001_hardcodedSecretDetected() {
        Path cfgPath = root.resolve("src/main/content/jcr_root/apps/myapp/osgiconfig/config/com.example.MyService.cfg.json");
        OSGiConfigNode config = new OSGiConfigNode(
                cfgPath.toString(), cfgPath, null,
                "com.example.MyService", null, false, Set.of(), ConfigFormat.CFG_JSON);

        ConfigPropertyNode badProp = new ConfigPropertyNode(
                config.getId(), cfgPath, new SourceLocation(cfgPath, 5, 4),
                "apiKey", "apiKey", "String", "sk_live_123456789", DataType.STRING, false);
        config.addProperty(badProp);

        ConfigPropertyNode goodProp = new ConfigPropertyNode(
                config.getId(), cfgPath, new SourceLocation(cfgPath, 6, 4),
                "servicePassword", "servicePassword", "String", "$[secret:MY_SERVICE_PASSWORD]", DataType.STRING, true);
        config.addProperty(goodProp);

        ConfigPropertyNode timeoutProp = new ConfigPropertyNode(
                config.getId(), cfgPath, new SourceLocation(cfgPath, 7, 4),
                "tokenTimeout", "tokenTimeout", "Integer", "3600", DataType.INTEGER, false);
        config.addProperty(timeoutProp);

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Map.of(config.getId(), config),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemCloud001Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals(RuleId.AEM_CLOUD_001.id(), violations.get(0).ruleId());
        assertEquals(Severity.ERROR, violations.get(0).severity());
        assertTrue(violations.get(0).message().contains("apiKey"));
    }

    @Test
    void testAemCloud002_deprecatedJspDetected(@TempDir Path tempDir) throws IOException {
        Path appsDir = tempDir.resolve("ui.apps/src/main/content/jcr_root/apps/myapp/components/teaser");
        Files.createDirectories(appsDir);
        Path jspFile = appsDir.resolve("teaser.jsp");
        Files.writeString(jspFile, "<%@page session=\"false\"%><div>JSP</div>");
        Path htmlFile = appsDir.resolve("teaser.html");
        Files.writeString(htmlFile, "<div>HTL</div>");

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), tempDir);
        List<Violation> violations = new AemCloud002Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals(RuleId.AEM_CLOUD_002.id(), violations.get(0).ruleId());
        assertEquals(Severity.WARNING, violations.get(0).severity());
        assertTrue(violations.get(0).message().contains("teaser.jsp"));
    }

    @Test
    void testAemSec001_unsafeHtlContextBypass() {
        Path htlPath = root.resolve("ui.apps/src/main/content/jcr_root/apps/myapp/components/text/text.html");
        HtlUnsafeContextFinding finding = new HtlUnsafeContextFinding(
                htlPath, 12, 18, "<div class=\"rich-text\">${properties.text @ context='unsafe'}</div>");

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), null);

        RuleContext ctx = new RuleContext(
                new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root,
                List.of(), List.of(), List.of(finding));

        List<Violation> violations = new AemSec001Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals(RuleId.AEM_SEC_001.id(), violations.get(0).ruleId());
        assertEquals(Severity.ERROR, violations.get(0).severity());
        assertTrue(violations.get(0).message().contains("context='unsafe'"));
    }

    @Test
    void testAemCloud001_localRunmodeIsWarning() {
        Path cfgPath = root.resolve(
                "ui.config/src/main/content/jcr_root/apps/myapp/osgiconfig/config.local/com.example.MyService.cfg.json");
        OSGiConfigNode config = new OSGiConfigNode(
                cfgPath.toString(), cfgPath, null, "com.example.MyService", null, false,
                Set.of("local"), ConfigFormat.CFG_JSON);
        config.addProperty(new ConfigPropertyNode(
                config.getId(), cfgPath, new SourceLocation(cfgPath, 3, 4),
                "apiKey", "apiKey", "String", "local-dev-key", DataType.STRING, false));

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Map.of(config.getId(), config),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(),
                null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemCloud001Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals(Severity.WARNING, violations.get(0).severity(),
                "config.local is not deployed by Cloud Manager");
    }

    /**
     * Regression test for issue #66: a {@code data-sly-element} attribute elsewhere on the same
     * template line used to downgrade the finding to WARNING, because the rule tested the whole
     * line with {@code snippet().contains("data-sly-element")}. The unsafe expression here is an
     * attribute value, not the element name, so nothing about it is element-name validated: the
     * finding must stay at the rule's ERROR level.
     */
    @Test
    void testAemSec001_dataSlyElementDoesNotDowngradeToWarning() {
        Path htlPath = root.resolve("ui.apps/components/tag/tag.html");
        HtlUnsafeContextFinding finding = new HtlUnsafeContextFinding(
                htlPath, 4, 18,
                "<div data-sly-element=\"${properties.tag}\" data-sly-attribute=\"${attrs @ context='unsafe'}\">");

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(),
                null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root,
                List.of(), List.of(), List.of(finding));
        List<Violation> violations = new AemSec001Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals(Severity.ERROR, violations.get(0).severity(),
                "data-sly-element on the same line must not downgrade the rule's ERROR level");
        assertEquals(RuleId.AEM_SEC_001.defaultLevel(), violations.get(0).severity());
    }

    /** Regression test for issue #66: the unsafe expression is in the element body, not its name. */
    @Test
    void testAemSec001_elementBodyUnsafeWithStaticDataSlyElementIsError() {
        Path htlPath = root.resolve("ui.apps/components/hero/hero.html");
        HtlUnsafeContextFinding finding = new HtlUnsafeContextFinding(
                htlPath, 1, 29,
                "<div data-sly-element=\"div\">${properties.body @ context='unsafe'}</div>");

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(),
                null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root,
                List.of(), List.of(), List.of(finding));
        List<Violation> violations = new AemSec001Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals(Severity.ERROR, violations.get(0).severity(),
                "an inert data-sly-element must not silence a stored-XSS sink in the element body");
    }

    @Test
    void testAemCloud003_unmappedSubserviceDetected() {
        Path javaPath = root.resolve("core/MyServiceImpl.java");
        io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall call =
                new io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall(
                        "unmapped-subservice", "com.example.MyServiceImpl", javaPath, 25, 12);

        Path cfgPath = root.resolve("ui.config/org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-other.cfg.json");
        OSGiConfigNode config = new OSGiConfigNode(
                cfgPath.toString(), cfgPath, null,
                "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~other",
                null, false, Set.of(), ConfigFormat.CFG_JSON);

        ConfigPropertyNode mappingProp = new ConfigPropertyNode(
                config.getId(), cfgPath, new SourceLocation(cfgPath, 2, 4),
                "user.mapping", "user.mapping", "String[]",
                "[\"com.example.core:other-service=[data-service]\"]", DataType.STRING, false);
        config.addProperty(mappingProp);

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Map.of(config.getId(), config),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(),
                List.of(call), null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemCloud003Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals(RuleId.AEM_CLOUD_003.id(), violations.get(0).ruleId());
        assertEquals(Severity.ERROR, violations.get(0).severity());
        assertTrue(violations.get(0).message().contains("unmapped-subservice"));
    }

    @Test
    void testAemCloud003_mappedSubservicePasses() {
        Path javaPath = root.resolve("core/MyServiceImpl.java");
        io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall call =
                new io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall(
                        "my-subservice", "com.example.MyServiceImpl", javaPath, 25, 12);

        Path cfgPath = root.resolve("ui.config/org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-my.cfg.json");
        OSGiConfigNode config = new OSGiConfigNode(
                cfgPath.toString(), cfgPath, null,
                "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~my",
                null, false, Set.of(), ConfigFormat.CFG_JSON);

        ConfigPropertyNode mappingProp = new ConfigPropertyNode(
                config.getId(), cfgPath, new SourceLocation(cfgPath, 2, 4),
                "user.mapping", "user.mapping", "String[]",
                "[\"com.example.core:my-subservice=[data-service]\"]", DataType.STRING, false);
        config.addProperty(mappingProp);

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Map.of(config.getId(), config),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(),
                List.of(call), null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemCloud003Rule().evaluate(ctx);

        assertEquals(0, violations.size());
    }

    @Test
    void testAemCloud003_genericYamlDiscoveredInArbitraryPath(@TempDir Path tempDir) throws IOException {
        Path yamlPath = tempDir.resolve("custom-module/config/security-rules.yaml");
        Files.createDirectories(yamlPath.getParent());
        Files.writeString(yamlPath,
                "- user_config:\n" +
                "    - generic-audit-user:\n" +
                "       - name:\n" +
                "         isSystemUser: true\n" +
                "         path: /home/users/system/custom\n");

        Path javaPath = tempDir.resolve("core/AuditServiceImpl.java");
        io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall call =
                new io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall(
                        "audit-subservice", "com.example.AuditServiceImpl", javaPath, 30, 8);

        Path cfgPath = tempDir.resolve("ui.config/org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-audit.cfg.json");
        OSGiConfigNode config = new OSGiConfigNode(
                cfgPath.toString(), cfgPath, null,
                "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~audit",
                null, false, Set.of(), ConfigFormat.CFG_JSON);

        ConfigPropertyNode mappingProp = new ConfigPropertyNode(
                config.getId(), cfgPath, new SourceLocation(cfgPath, 2, 4),
                "user.mapping", "user.mapping", "String[]",
                "[\"com.example.core:audit-subservice=[generic-audit-user]\"]", DataType.STRING, false);
        config.addProperty(mappingProp);

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Map.of(config.getId(), config),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(),
                List.of(call), null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), tempDir);
        List<Violation> violations = new AemCloud003Rule().evaluate(ctx);

        assertEquals(0, violations.size());
    }

    @Test
    void testAemCloud003_fileVaultJcrXmlDiscovered(@TempDir Path tempDir) throws IOException {
        Path jcrPath = tempDir.resolve("ui.content/jcr_root/home/users/system/vault-svc-user/.content.xml");
        Files.createDirectories(jcrPath.getParent());
        Files.writeString(jcrPath,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<jcr:root xmlns:jcr=\"http://www.jcp.org/jcr/1.0\" xmlns:rep=\"internal\"\n" +
                "    jcr:primaryType=\"rep:SystemUser\"\n" +
                "    rep:authorizableId=\"vault-svc-user\"/>\n");

        Path javaPath = tempDir.resolve("core/VaultServiceImpl.java");
        io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall call =
                new io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall(
                        "vault-subservice", "com.example.VaultServiceImpl", javaPath, 35, 8);

        Path cfgPath = tempDir.resolve("ui.config/org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-vault.cfg.json");
        OSGiConfigNode config = new OSGiConfigNode(
                cfgPath.toString(), cfgPath, null,
                "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~vault",
                null, false, Set.of(), ConfigFormat.CFG_JSON);

        ConfigPropertyNode mappingProp = new ConfigPropertyNode(
                config.getId(), cfgPath, new SourceLocation(cfgPath, 2, 4),
                "user.mapping", "user.mapping", "String[]",
                "[\"com.example.core:vault-subservice=[vault-svc-user]\"]", DataType.STRING, false);
        config.addProperty(mappingProp);

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Map.of(config.getId(), config),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(),
                List.of(call), null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), tempDir);
        List<Violation> violations = new AemCloud003Rule().evaluate(ctx);

        assertEquals(0, violations.size());
    }

    @Test
    void testAemCloud003_unresolvedServiceUserFlagged() {
        Path javaPath = root.resolve("core/GhostServiceImpl.java");
        io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall call =
                new io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall(
                        "ghost-subservice", "com.example.GhostServiceImpl", javaPath, 40, 8);

        Path cfgPath = root.resolve("ui.config/org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-ghost.cfg.json");
        OSGiConfigNode config = new OSGiConfigNode(
                cfgPath.toString(), cfgPath, null,
                "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~ghost",
                null, false, Set.of(), ConfigFormat.CFG_JSON);

        ConfigPropertyNode mappingProp = new ConfigPropertyNode(
                config.getId(), cfgPath, new SourceLocation(cfgPath, 2, 4),
                "user.mapping", "user.mapping", "String[]",
                "[\"com.example.core:ghost-subservice=[ghost-user-404]\"]", DataType.STRING, false);
        config.addProperty(mappingProp);

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Map.of(config.getId(), config),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(),
                List.of(call), null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemCloud003Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals("UNRESOLVED_SERVICE_USER", violations.get(0).subtype());
        assertEquals(Severity.WARNING, violations.get(0).severity());
        assertTrue(violations.get(0).message().contains("ghost-user-404"));
    }

    @Test
    void testAemCloud003_nullSubserviceWithSubserviceOnlyMappingsFailsOpen() {
        Path javaPath = root.resolve("core/DefaultLoginServiceImpl.java");
        io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall call =
                new io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall(
                        null, "com.example.DefaultLoginServiceImpl", javaPath, 42, 8,
                        io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall.Resolution.NULL);

        Path cfgPath = root.resolve("ui.config/org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-x.cfg.json");
        OSGiConfigNode config = new OSGiConfigNode(
                cfgPath.toString(), cfgPath, null,
                "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~x",
                null, false, Set.of(), ConfigFormat.CFG_JSON);
        config.addProperty(new ConfigPropertyNode(
                config.getId(), cfgPath, new SourceLocation(cfgPath, 2, 4),
                "user.mapping", "user.mapping", "String[]",
                "[\"com.example.core:some-service=[some-user]\"]", DataType.STRING, false));

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Map.of(config.getId(), config),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(),
                List.of(call), null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemCloud003Rule().evaluate(ctx);

        assertTrue(violations.stream().noneMatch(v -> "UNMAPPED_DEFAULT_SUBSERVICE".equals(v.subtype())),
                () -> "Existing mapper config should make a null-subservice call fail open: " + violations);
    }

    @Test
    void testAemCloud003_nullSubserviceWithoutDefaultMappingFlagged() {
        Path javaPath = root.resolve("core/DefaultLoginServiceImpl.java");
        io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall call =
                new io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall(
                        null, "com.example.DefaultLoginServiceImpl", javaPath, 42, 8);

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(),
                List.of(call), null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemCloud003Rule().evaluate(ctx);

        assertEquals(1, violations.size());
        assertEquals("UNMAPPED_DEFAULT_SUBSERVICE", violations.get(0).subtype());
        assertEquals(Severity.ERROR, violations.get(0).severity());
    }

    @Test
    void testAemCloud003_unresolvedSubserviceConstantIsNotFlagged() {
        Path javaPath = root.resolve("core/ImportJobExecutor.java");
        io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall call =
                new io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall(
                        "IMPORT_SERVICE_SUBSERVICE_NAME", "com.example.ImportJobExecutor", javaPath, 222, 42);

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(),
                List.of(call), null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemCloud003Rule().evaluate(ctx);

        assertTrue(violations.stream().noneMatch(v -> "UNMAPPED_SUBSERVICE_USER".equals(v.subtype())),
                () -> "An unresolved Java constant must fail open, not ERROR: " + violations);
    }

    @Test
    void testAemCloud003_unresolvedVariableIsNotFlaggedAsNull() {
        Path javaPath = root.resolve("core/ImportJobExecutor.java");
        io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall call =
                new io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall(
                        null, "com.example.ImportJobExecutor", javaPath, 727, 42,
                        io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall.Resolution.UNRESOLVED);

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(),
                List.of(call), null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemCloud003Rule().evaluate(ctx);

        assertTrue(violations.stream().noneMatch(v -> "UNMAPPED_DEFAULT_SUBSERVICE".equals(v.subtype())),
                () -> "An unresolved map variable must fail open: " + violations);
    }

    @Test
    void testAemCloud003_nullSubserviceWithBundleLevelMappingIsMapped() {
        Path javaPath = root.resolve("core/DefaultLoginServiceImpl.java");
        io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall call =
                new io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall(
                        null, "com.example.DefaultLoginServiceImpl", javaPath, 42, 8);

        Path cfgPath = root.resolve("ui.config/org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-default.cfg.json");
        OSGiConfigNode config = new OSGiConfigNode(
                cfgPath.toString(), cfgPath, null,
                "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~default",
                null, false, Set.of(), ConfigFormat.CFG_JSON);
        config.addProperty(new ConfigPropertyNode(
                config.getId(), cfgPath, new SourceLocation(cfgPath, 2, 4),
                "user.mapping", "user.mapping", "String[]",
                "[\"com.example.core=[default-svc-user]\"]", DataType.STRING, false));

        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                new ReferenceGraph(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Map.of(config.getId(), config),
                Collections.emptyMap(), Collections.emptyMap(), List.of(), List.of(), List.of(), List.of(),
                List.of(call), null);

        RuleContext ctx = new RuleContext(new ReferenceGraph(), buildResult, Set.of(), null, Set.of(), root);
        List<Violation> violations = new AemCloud003Rule().evaluate(ctx);

        assertTrue(violations.stream().noneMatch(v -> "UNMAPPED_DEFAULT_SUBSERVICE".equals(v.subtype())),
                () -> "Bundle-level mapping must satisfy a null subservice: " + violations);
    }
}
