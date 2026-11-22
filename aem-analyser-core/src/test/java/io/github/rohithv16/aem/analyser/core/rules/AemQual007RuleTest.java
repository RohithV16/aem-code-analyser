package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraph;
import io.github.rohithv16.aem.analyser.core.graph.ReferenceGraphBuilder;
import io.github.rohithv16.aem.analyser.core.model.graph.node.SlingModelNode;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParser;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual007Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AEM-QUAL-007 against the issue's fixtures: a request-only injection in a Resource-only model, the
 * same model with both adaptables, and the guards that keep optional injections, non-request types
 * and Object-adaptable models silent. The {@code @Via} case threads a Resource through
 * {@code ChildResource} into a request-only model.
 */
class AemQual007RuleTest {

    @TempDir
    Path root;

    @Test
    void requestInjectionInResourceOnlyModelIsReported() throws Exception {
        SlingModelNode model = parse("HeroModel", """
                package com.acme.models;

                import org.apache.sling.api.SlingHttpServletRequest;
                import org.apache.sling.api.resource.Resource;
                import org.apache.sling.models.annotations.Model;
                import org.apache.sling.models.annotations.injectorspecific.Inject;

                @Model(adaptables = Resource.class)
                public class HeroModel {
                    @Inject
                    private SlingHttpServletRequest request;
                }
                """);

        List<Violation> violations = evaluate(Map.of(model.getFqn(), model));

        assertEquals(1, violations.size(), () -> "Required request injection cannot be satisfied: " + violations);
        assertEquals("REQUEST_INJECTION_WITHOUT_REQUEST_ADAPTABLE", violations.get(0).subtype());
        assertTrue(violations.get(0).region().startLine() > 1, "the field line is reported");
        assertEquals("request", violations.get(0).targetPid());
    }

    @Test
    void requestAdaptableOrHttpServletRequestAdaptableIsSilent() throws Exception {
        SlingModelNode model = parse("HeroModel", """
                package com.acme.models;

                import org.apache.sling.api.SlingHttpServletRequest;
                import org.apache.sling.api.resource.Resource;
                import org.apache.sling.models.annotations.Model;
                import org.apache.sling.models.annotations.injectorspecific.Inject;

                @Model(adaptables = {Resource.class, SlingHttpServletRequest.class})
                public class HeroModel {
                    @Inject
                    private SlingHttpServletRequest request;
                }
                """);

        assertTrue(evaluate(Map.of(model.getFqn(), model)).isEmpty(),
                "A model declaring the request adaptable can inject it");
    }

    @Test
    void optionalOrModelWideOptionalInjectionIsSilent() throws Exception {
        SlingModelNode optional = parse("OptionalModel", """
                package com.acme.models;

                import org.apache.sling.api.SlingHttpServletRequest;
                import org.apache.sling.api.resource.Resource;
                import org.apache.sling.models.annotations.Model;
                import org.apache.sling.models.annotations.Optional;
                import org.apache.sling.models.annotations.injectorspecific.Inject;

                @Model(adaptables = Resource.class)
                public class OptionalModel {
                    @Optional
                    @Inject
                    private SlingHttpServletRequest request;
                }
                """);
        SlingModelNode modelWide = parse("OptionalByDefault", """
                package com.acme.models;

                import org.apache.sling.api.SlingHttpServletRequest;
                import org.apache.sling.api.resource.Resource;
                import org.apache.sling.models.annotations.DefaultInjectionStrategy;
                import org.apache.sling.models.annotations.Model;
                import org.apache.sling.models.annotations.injectorspecific.Inject;

                @Model(adaptables = Resource.class,
                       defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
                public class OptionalByDefault {
                    @Inject
                    private SlingHttpServletRequest request;
                }
                """);

        Map<String, SlingModelNode> models = new LinkedHashMap<>();
        models.put(optional.getFqn(), optional);
        models.put(modelWide.getFqn(), modelWide);
        assertTrue(evaluate(models).isEmpty(),
                "An optional injection leaves null and the model still registers");
    }

    @Test
    void requestAttributeInResourceOnlyModelIsReported() throws Exception {
        SlingModelNode model = parse("AttributeModel", """
                package com.acme.models;

                import org.apache.sling.api.resource.Resource;
                import org.apache.sling.models.annotations.Model;
                import org.apache.sling.models.annotations.injectorspecific.RequestAttribute;

                @Model(adaptables = Resource.class)
                public class AttributeModel {
                    @RequestAttribute(name = "trackingId")
                    private String trackingId;
                }
                """);

        List<Violation> violations = evaluate(Map.of(model.getFqn(), model));

        assertEquals(1, violations.size(), () -> "@RequestAttribute needs the request: " + violations);
        assertEquals("REQUEST_INJECTION_WITHOUT_REQUEST_ADAPTABLE", violations.get(0).subtype());
    }

    @Test
    void nonRequestTypeAndObjectAdaptableAreSilent() throws Exception {
        SlingModelNode model = parse("ResolverModel", """
                package com.acme.models;

                import org.apache.sling.api.resource.Resource;
                import org.apache.sling.api.resource.ResourceResolver;
                import org.apache.sling.models.annotations.Model;
                import org.apache.sling.models.annotations.injectorspecific.Inject;
                import org.apache.sling.models.annotations.injectorspecific.SlingObject;

                @Model(adaptables = Resource.class)
                public class ResolverModel {
                    @SlingObject
                    private ResourceResolver resolver;

                    @Inject
                    private String title;
                }
                """);
        SlingModelNode objectModel = parse("AnythingModel", """
                package com.acme.models;

                import org.apache.sling.api.SlingHttpServletRequest;
                import org.apache.sling.models.annotations.Model;
                import org.apache.sling.models.annotations.injectorspecific.Inject;

                @Model(adaptables = Object.class)
                public class AnythingModel {
                    @Inject
                    private SlingHttpServletRequest request;
                }
                """);

        Map<String, SlingModelNode> models = new LinkedHashMap<>();
        models.put(model.getFqn(), model);
        models.put(objectModel.getFqn(), objectModel);
        assertTrue(evaluate(models).isEmpty(),
                "Non-request types and Object-adaptable models are not judged");
    }

    @Test
    void viaChildResourceIntoRequestOnlyModelIsReported() throws Exception {
        SlingModelNode child = parse("ChildModel", """
                package com.acme.models;

                import org.apache.sling.api.SlingHttpServletRequest;
                import org.apache.sling.models.annotations.Model;

                @Model(adaptables = SlingHttpServletRequest.class)
                public class ChildModel {
                }
                """);
        SlingModelNode parent = parse("ParentModel", """
                package com.acme.models;

                import org.apache.sling.api.resource.Resource;
                import org.apache.sling.models.annotations.Model;
                import org.apache.sling.models.annotations.Via;
                import org.apache.sling.models.annotations.injectorspecific.Inject;
                import org.apache.sling.models.annotations.via.ChildResource;

                @Model(adaptables = Resource.class)
                public class ParentModel {
                    @Inject
                    @Via(type = ChildResource.class, value = "jcr:content")
                    private ChildModel child;
                }
                """);

        Map<String, SlingModelNode> models = new LinkedHashMap<>();
        models.put(child.getFqn(), child);
        models.put(parent.getFqn(), parent);
        List<Violation> violations = evaluate(models);

        assertEquals(1, violations.size(), () -> "The via target cannot accept a Resource: " + violations);
        assertEquals("VIA_ADAPTABLE_MISMATCH", violations.get(0).subtype());
        assertEquals("child", violations.get(0).targetPid());
    }

    @Test
    void viaIntoResourceModelIsSilent() throws Exception {
        SlingModelNode child = parse("ChildModel", """
                package com.acme.models;

                import org.apache.sling.api.resource.Resource;
                import org.apache.sling.models.annotations.Model;

                @Model(adaptables = Resource.class)
                public class ChildModel {
                }
                """);
        SlingModelNode parent = parse("ParentModel", """
                package com.acme.models;

                import org.apache.sling.api.resource.Resource;
                import org.apache.sling.models.annotations.Model;
                import org.apache.sling.models.annotations.Via;
                import org.apache.sling.models.annotations.injectorspecific.Inject;
                import org.apache.sling.models.annotations.via.ChildResource;

                @Model(adaptables = Resource.class)
                public class ParentModel {
                    @Inject
                    @Via(type = ChildResource.class, value = "jcr:content")
                    private ChildModel child;
                }
                """);

        Map<String, SlingModelNode> models = new LinkedHashMap<>();
        models.put(child.getFqn(), child);
        models.put(parent.getFqn(), parent);
        assertTrue(evaluate(models).isEmpty(),
                "A ChildResource via produces a Resource the target model declares");
    }

    private SlingModelNode parse(String name, String source) throws Exception {
        Path sourceDir = root.resolve("core/src/main/java/com/acme/models");
        Files.createDirectories(sourceDir);
        Path file = sourceDir.resolve(name + ".java");
        Files.writeString(file, source);
        return new JavaSourceParser().parse(file).slingModels().get(0);
    }

    private List<Violation> evaluate(Map<String, SlingModelNode> models) {
        ReferenceGraph graph = new ReferenceGraph();
        models.values().forEach(graph::addVertex);
        ReferenceGraphBuilder.GraphBuildResult buildResult = new ReferenceGraphBuilder.GraphBuildResult(
                graph, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), models,
                List.of(), List.of(), List.of(), List.of(), null);
        RuleContext context = new RuleContext(graph, buildResult, Collections.emptySet(), null,
                Collections.emptySet(), root);
        return new AemQual007Rule().evaluate(context);
    }
}
