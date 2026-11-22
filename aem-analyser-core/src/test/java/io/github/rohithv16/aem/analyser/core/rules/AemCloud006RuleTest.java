package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud006Rule;
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
 * AEM-CLOUD-006 against the four defect shapes from the issue, plus the guards that keep a stock AEM
 * SDK dispatcher module silent: resolving includes, wildcard/variable targets and build-output copies.
 */
class AemCloud006RuleTest {

    @TempDir
    Path root;

    @Test
    void unresolvedIncludeDuplicateServerNameMissingDocumentRootAndLegacyAnyAreReported() throws Exception {
        Path vhosts = root.resolve("dispatcher/src/conf.d/available_vhosts");
        Files.createDirectories(vhosts);
        Files.writeString(vhosts.resolve("a.vhost"), """
                Include conf.d/includes/nope.any

                <VirtualHost *:80>
                    ServerName "www.acme.com"
                    DocumentRoot "${DOCROOT}"
                </VirtualHost>
                """);
        Files.writeString(vhosts.resolve("b.vhost"), """
                <VirtualHost *:80>
                    ServerName "www.acme.com"
                </VirtualHost>
                """);
        Files.writeString(root.resolve("dispatcher/src/dispatcher.any"),
                "/farms {\n\t$include \"farms/*.farm\"\n}\n");

        List<Violation> violations = evaluate();

        assertEquals(4, violations.size(), () -> "Expected exactly the four shapes: " + violations);
        Set<String> subtypes = violations.stream().map(Violation::subtype).collect(Collectors.toSet());
        assertEquals(Set.of("UNRESOLVED_DISPATCHER_INCLUDE", "DUPLICATE_SERVER_NAME",
                "MISSING_DOCUMENT_ROOT", "LEGACY_DISPATCHER_ANY"), subtypes);
        Violation include = bySubtype(violations, "UNRESOLVED_DISPATCHER_INCLUDE");
        assertEquals(1, include.region().startLine(), "the include line, not the file, is reported");
        assertTrue(include.filePath().endsWith("a.vhost"),
                () -> "The unresolved include belongs to a.vhost: " + include);
        assertEquals(1, bySubtype(violations, "DUPLICATE_SERVER_NAME").relatedLocations().size(),
                "the duplicate points at the vhost that claimed the name first");
    }

    @Test
    void resolvingIncludesGlobTargetsAndVariablesAreSilent() throws Exception {
        Path conf = root.resolve("dispatcher/src/conf.d");
        Files.createDirectories(conf.resolve("variables"));
        Files.createDirectories(conf.resolve("includes"));
        Files.createDirectories(conf.resolve("enabled_vhosts"));
        Files.writeString(conf.resolve("variables/custom.vars"), "Define CONTENT_FOLDER_NAME acme\n");
        Files.writeString(conf.resolve("includes/cache.conf"), "<LocationMatch \"^/content/.*\">\n</LocationMatch>\n");
        Files.createDirectories(conf.resolve("available_vhosts"));
        Files.writeString(conf.resolve("available_vhosts/acme.vhost"), """
                Include conf.d/variables/custom.vars
                Include conf.d/enabled_vhosts/*.vhost
                Include ${VHOST_INCLUDE}

                <VirtualHost *:80>
                    ServerName "www.acme.com"
                    DocumentRoot "${DOCROOT}"
                    <IfModule mod_headers.c>
                        Include conf.d/includes/cache.conf
                    </IfModule>
                </VirtualHost>
                """);
        Path dispatcherConf = root.resolve("dispatcher/src/conf.dispatcher.d");
        Files.createDirectories(dispatcherConf.resolve("cache"));
        Files.writeString(dispatcherConf.resolve("cache/rules.any"), "/0000 { /type \"allow\" }\n");
        Files.writeString(dispatcherConf.resolve("dispatcher.any"), """
                /farms {
                    $include "enabled_farms/*.farm"
                }
                /cache {
                    $include "cache/rules.any"
                }
                """);

        assertTrue(evaluate().isEmpty(),
                "Existing includes, glob includes and ${...} targets must not be reported");
    }

    @Test
    void buildOutputAndCommentedOutBlocksAreIgnored() throws Exception {
        Path vhosts = root.resolve("dispatcher/src/conf.d/available_vhosts");
        Files.createDirectories(vhosts);
        Files.writeString(vhosts.resolve("acme.vhost"), """
                # Include conf.d/includes/commented-out.conf
                #<VirtualHost *:80>
                #    ServerName "www.acme.com"
                #</VirtualHost>

                <VirtualHost *:80>
                    ServerName "www.acme.com"
                    DocumentRoot "${DOCROOT}"
                </VirtualHost>
                """);
        Path copy = root.resolve("dispatcher/target/conf.d/available_vhosts");
        Files.createDirectories(copy);
        Files.writeString(copy.resolve("stale.vhost"), """
                Include conf.d/includes/does-not-exist.conf
                <VirtualHost *:80>
                    ServerName "www.acme.com"
                </VirtualHost>
                """);

        assertTrue(evaluate().isEmpty(),
                "A commented-out block and a target/ copy of the module must not produce findings");
    }

    @Test
    void runtimeProvidedScaffoldAndSymlinkedVhostsAreIgnored() throws Exception {
        Path conf = root.resolve("dispatcher/src/conf.d");
        Files.createDirectories(conf.resolve("available_vhosts"));
        Files.createDirectories(conf.resolve("enabled_vhosts"));
        Files.writeString(conf.resolve("available_vhosts/default.vhost"), """
                <VirtualHost *:80>
                    ServerName "publish"
                    DocumentRoot "${DOCROOT}"
                </VirtualHost>
                """);
        Files.writeString(conf.resolve("dispatcher_vhost.conf"), """
                Include conf.d/variables/default.vars
                Include conf.d/enabled_vhosts/*.vhost
                """);
        boolean symlinks;
        try {
            Files.createSymbolicLink(conf.resolve("enabled_vhosts/default.vhost"),
                    conf.resolve("available_vhosts/default.vhost"));
            symlinks = true;
        } catch (UnsupportedOperationException | java.io.IOException notSupported) {
            symlinks = false;
        }

        if (symlinks) {
            assertTrue(evaluate().isEmpty(), "The enabled_* symlink must not double-report a vhost and "
                    + "the runtime-provided dispatcher_vhost.conf must not be judged");
        }
    }

    @Test
    void projectWithoutADispatcherModuleIsSilent() throws Exception {
        Files.createDirectories(root.resolve("core/src/main/java/com/acme"));
        Files.writeString(root.resolve("core/src/main/java/com/acme/Acme.java"),
                "package com.acme;\nclass Acme { }\n");

        assertTrue(evaluate().isEmpty());
    }

    private Violation bySubtype(List<Violation> violations, String subtype) {
        return violations.stream().filter(v -> subtype.equals(v.subtype())).findFirst()
                .orElseThrow(() -> new AssertionError("No " + subtype + " in " + violations));
    }

    private List<Violation> evaluate() {
        ReferenceGraph graph = new ReferenceGraph();
        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), List.of(), List.of(), null);
        RuleContext context = new RuleContext(graph, buildResult, Collections.emptySet(), null,
                Collections.emptySet(), root);
        return new AemCloud006Rule().evaluate(context);
    }
}
