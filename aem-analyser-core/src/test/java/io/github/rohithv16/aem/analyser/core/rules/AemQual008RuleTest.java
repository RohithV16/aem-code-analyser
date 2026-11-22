package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParser;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual008Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AEM-QUAL-008 must report a mandatory reference whose target is declared by the project but
 * registered by no component, and must stay silent for optional cardinality, targets outside the
 * project, and satisfied references.
 */
class AemQual008RuleTest {

    private static final String PROVIDER = "com.acme.services.ResourceResolverProvider";

    @TempDir
    Path root;

    @Test
    void unprovidedMandatoryReferenceIsFlaggedAtItsDeclaration() {
        Path orchestratorFile = root.resolve("core/src/main/java/com/acme/impl/Orchestrator.java");
        Path providerFile = root.resolve("core/src/main/java/com/acme/services/ResourceResolverProvider.java");
        OSGiComponentNode orchestrator = component("com.acme.impl.Orchestrator", orchestratorFile,
                Set.of("com.acme.impl.Orchestrator"),
                List.of(new OSGiComponentNode.ServiceReference("resolverProvider", PROVIDER,
                        OSGiComponentNode.ReferenceCardinality.MANDATORY,
                        new SourceLocation(orchestratorFile, 29, 3))));

        List<Violation> violations = evaluate(List.of(orchestrator),
                Map.of(PROVIDER, classNode(PROVIDER, providerFile)));

        assertEquals(1, violations.size(), () -> "Expected exactly one finding: " + violations);
        assertEquals(RuleId.AEM_QUAL_008.id(), violations.get(0).ruleId());
        assertEquals("UNPROVIDED_MANDATORY_REFERENCE", violations.get(0).subtype());
        assertEquals(29, violations.get(0).region().startLine());
        assertTrue(violations.get(0).message().contains(PROVIDER),
                () -> "Message must name the unprovided service: " + violations.get(0).message());
    }

    @Test
    void registeredProviderSilencesTheReference() {
        Path file = root.resolve("core/src/main/java/com/acme/impl/Orchestrator.java");
        OSGiComponentNode orchestrator = component("com.acme.impl.Orchestrator", file, Set.of(),
                List.of(new OSGiComponentNode.ServiceReference("resolverProvider", PROVIDER,
                        OSGiComponentNode.ReferenceCardinality.MANDATORY,
                        new SourceLocation(file, 12, 3))));
        OSGiComponentNode provider = component("com.acme.impl.ResourceResolverProviderImpl",
                root.resolve("core/src/main/java/com/acme/impl/ResourceResolverProviderImpl.java"),
                Set.of(PROVIDER), List.of());

        assertTrue(evaluate(List.of(orchestrator, provider),
                        Map.of(PROVIDER, classNode(PROVIDER,
                                root.resolve("core/src/main/java/com/acme/services/ResourceResolverProvider.java"))))
                        .isEmpty(),
                "A service registered by a component satisfies the reference");
    }

    @Test
    void optionalCardinalityIsNotReported() {
        Path file = root.resolve("core/src/main/java/com/acme/impl/Orchestrator.java");
        OSGiComponentNode orchestrator = component("com.acme.impl.Orchestrator", file, Set.of(),
                List.of(new OSGiComponentNode.ServiceReference("resolverProvider", PROVIDER,
                        OSGiComponentNode.ReferenceCardinality.OPTIONAL,
                        new SourceLocation(file, 12, 3))));

        assertTrue(evaluate(List.of(orchestrator),
                Map.of(PROVIDER, classNode(PROVIDER, root.resolve("Provider.java")))).isEmpty(),
                "OPTIONAL references activate without a provider");
    }

    @Test
    void targetOutsideTheProjectIsNotJudged() {
        Path file = root.resolve("core/src/main/java/com/acme/impl/Orchestrator.java");
        OSGiComponentNode orchestrator = component("com.acme.impl.Orchestrator", file, Set.of(),
                List.of(new OSGiComponentNode.ServiceReference("scheduler",
                        "org.apache.sling.commons.scheduler.Scheduler",
                        OSGiComponentNode.ReferenceCardinality.MANDATORY,
                        new SourceLocation(file, 12, 3))));

        assertTrue(evaluate(List.of(orchestrator), Map.of()).isEmpty(),
                "A platform service is not declared by the project and must not be judged");
    }

    /**
     * The cardinality must survive the real parser: a rule that cannot see OPTIONAL would flood a
     * project with findings for every deliberately optional injection.
     */
    @Test
    void parserCapturesCardinalityAndRejectsOptionalReferences() throws Exception {
        Path provider = root.resolve("com/acme/services/ResourceResolverProvider.java");
        Files.createDirectories(provider.getParent());
        Files.writeString(provider, """
                package com.acme.services;
                public interface ResourceResolverProvider { }
                """);
        Path orchestrator = root.resolve("com/acme/impl/Orchestrator.java");
        Files.createDirectories(orchestrator.getParent());
        Files.writeString(orchestrator, """
                package com.acme.impl;
                import com.acme.services.ResourceResolverProvider;
                import org.osgi.service.component.annotations.Component;
                import org.osgi.service.component.annotations.Reference;
                import org.osgi.service.component.annotations.ReferenceCardinality;

                @Component(service = Orchestrator.class)
                public class Orchestrator {
                    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
                    private ResourceResolverProvider resolverProvider;
                }
                """);

        OSGiComponentNode parsed = new JavaSourceParser().parse(orchestrator).components().stream()
                .filter(component -> component.getComponentClassFqn().endsWith("Orchestrator"))
                .findFirst()
                .orElseThrow();

        assertEquals(1, parsed.getReferenceDetails().size());
        assertEquals(OSGiComponentNode.ReferenceCardinality.OPTIONAL,
                parsed.getReferenceDetails().get(0).cardinality());

        List<Violation> violations = evaluateWithGraph(List.of(parsed),
                Map.of(PROVIDER, classNode(PROVIDER, provider)));
        assertTrue(violations.isEmpty(),
                () -> "A parsed OPTIONAL reference must not be reported: " + violations);
    }

    private OSGiComponentNode component(String fqn, Path file, Set<String> services,
                                        List<OSGiComponentNode.ServiceReference> references) {
        return new OSGiComponentNode(fqn, fqn, JavaClassNode.Kind.CLASS, Set.of(), null, Set.of(),
                file, new SourceLocation(file, 1, 1), List.of(),
                OSGiComponentNode.ConfigurationPolicy.OPTIONAL, true, true, services,
                Collections.emptyMap(), null, Set.of(), List.of(), null, true, false, references);
    }

    private JavaClassNode classNode(String fqn, Path file) {
        return new JavaClassNode(fqn, JavaClassNode.Kind.INTERFACE, Set.of(), null, Set.of(),
                file, new SourceLocation(file, 1, 1));
    }

    private List<Violation> evaluate(List<OSGiComponentNode> components,
                                     Map<String, JavaClassNode> classes) {
        return evaluateWithGraph(components, classes);
    }

    private List<Violation> evaluateWithGraph(List<OSGiComponentNode> components,
                                              Map<String, JavaClassNode> classes) {
        Map<String, OSGiComponentNode> componentsByFqn = new LinkedHashMap<>();
        components.forEach(component -> componentsByFqn.put(component.getComponentClassFqn(), component));
        ReferenceGraph graph = new ReferenceGraph();
        components.forEach(graph::addVertex);
        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(), componentsByFqn,
                Collections.emptyMap(), new LinkedHashMap<>(classes), Collections.emptyMap(),
                List.of(), List.of(), null);
        RuleContext context = new RuleContext(graph, buildResult, Collections.emptySet(), null,
                Collections.emptySet(), root);
        return new AemQual008Rule().evaluate(context);
    }
}
