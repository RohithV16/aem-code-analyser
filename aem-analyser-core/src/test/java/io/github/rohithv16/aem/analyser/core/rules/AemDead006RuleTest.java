package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.discovery.FileInventory;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead006Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AEM-DEAD-006 against the issue's cases: a used key missing from {@code en.json}, the same key
 * present, computed keys, and the portfolio shape where only a non-baseline dictionary exists.
 */
class AemDead006RuleTest {

    @TempDir
    Path root;

    @Test
    void missingKeysInTemplatesAndModelsAreReported() throws Exception {
        dictionary("en.json", """
                {
                  "welcome": "Welcome",
                  "cart.empty": "Your cart is empty"
                }
                """);
        dictionary("fr.json", """
                {
                  "welcome": "Bienvenue"
                }
                """);
        template("i18n.html", """
                <h1>${'welcome' @ i18n}</h1>
                <h2>${'checkout.title' @ i18n}</h2>
                <p>${model.dynamicKey @ i18n}</p>
                <p>${'welcome' @ context='text'}</p>
                """);
        javaSource("""
                package com.acme.models;

                import org.apache.sling.i18n.I18n;

                class Copy {
                    private I18n i18n;

                    String cart() {
                        return i18n.get("cart.empty");
                    }

                    String title() {
                        return i18n.get("java.missing", "arg");
                    }

                    String dynamic() {
                        return i18n.get(KEY);
                    }

                    // i18n.get("commented.out")
                    /* i18n.get("also.commented") */
                    String comment() {
                        return "";
                    }
                }
                """);

        List<Violation> violations = evaluate();

        assertEquals(2, violations.size(), () -> "One HTL key and one Java key are missing: " + violations);
        Set<String> keys = violations.stream().map(Violation::targetPid).collect(Collectors.toSet());
        assertEquals(Set.of("checkout.title", "java.missing"), keys);
        Violation htl = violations.stream()
                .filter(v -> v.filePath().endsWith("i18n.html")).findFirst().orElseThrow();
        assertEquals(2, htl.region().startLine(), "the missing key is reported on its own line");
        assertEquals("MISSING_TRANSLATION_KEY", htl.subtype());
    }

    @Test
    void nonBaselineDictionaryOnlyIsOneInfo() throws Exception {
        dictionary("fr.json", """
                {
                  "welcome": "Bienvenue"
                }
                """);
        dictionary(".content.xml", "<jcr:root/>");

        List<Violation> violations = evaluate();

        assertEquals(1, violations.size(), () -> "Only the missing baseline is reported: " + violations);
        Violation info = violations.get(0);
        assertEquals(Severity.INFO, info.severity());
        assertEquals("MISSING_BASELINE_DICTIONARY", info.subtype());
        assertTrue(info.message().contains("fr"), () -> "the message names the locale found: " + info.message());
        assertTrue(info.filePath().endsWith("fr.json"),
                () -> "the finding points at the locale dictionary, not vault metadata: " + info.filePath());
    }

    @Test
    void nonBaselineDictionaryWithoutAnyUsageStillReportsTheMissingBaseline() throws Exception {
        dictionary("fr.json", "{\"welcome\": \"Bienvenue\"}");

        List<Violation> violations = evaluate();

        assertEquals(1, violations.size(), () -> "The portfolio shape is one INFO: " + violations);
        assertEquals(Severity.INFO, violations.get(0).severity());
    }

    @Test
    void xmlBaselineMakesTheCheckUnknownAndSilent() throws Exception {
        dictionary("en.xml", "<properties><entry key=\"welcome\">Welcome</entry></properties>");
        template("i18n.html", "<h1>${'anything.at.all' @ i18n}</h1>\n");

        assertTrue(evaluate().isEmpty(),
                "An XML dictionary is a baseline this rule cannot read, so it must stay silent");
    }

    @Test
    void projectWithoutI18nIsSilent() throws Exception {
        template("plain.html", "<h1>${properties.title}</h1>\n");

        assertTrue(evaluate().isEmpty(), "No dictionary and no i18n usage is not a finding");
    }

    private void dictionary(String name, String content) throws Exception {
        Path i18n = root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/i18n");
        Files.createDirectories(i18n);
        Files.writeString(i18n.resolve(name), content);
    }

    private void template(String name, String content) throws Exception {
        Path component = root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/components/zzprobe");
        Files.createDirectories(component);
        Files.writeString(component.resolve(name), content);
    }

    private void javaSource(String content) throws Exception {
        Path source = root.resolve("core/src/main/java/com/acme/models");
        Files.createDirectories(source);
        Files.writeString(source.resolve("Copy.java"), content);
    }

    private List<Violation> evaluate() {
        ReferenceGraph graph = new ReferenceGraph();
        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), List.of(), List.of(), null);
        RuleContext context = new RuleContext(graph, buildResult, Collections.emptySet(), null,
                Collections.emptySet(), root, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), FileInventory.scan(root));
        return new AemDead006Rule().evaluate(context);
    }
}
