package io.github.rohithv16.aem.analyser.core.parser.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import io.github.rohithv16.aem.analyser.core.model.perf.ResourceLeakFinding;
import io.github.rohithv16.aem.analyser.core.parser.java.visitor.ResourceLeakVisitor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ResourceLeakVisitorTest {

    private final JavaParser parser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    private final ResourceLeakVisitor visitor = new ResourceLeakVisitor();

    @Test
    void testDetectsUnclosedResourceResolver() {
        String code = """
                package com.example;
                import org.apache.sling.api.resource.ResourceResolverFactory;
                import org.apache.sling.api.resource.ResourceResolver;
                public class LeakyService {
                    ResourceResolverFactory factory;
                    public void doWork() throws Exception {
                        ResourceResolver resolver = factory.getServiceResourceResolver(null);
                        resolver.getResource("/content");
                        // resolver is never closed!
                    }
                }
                """;
        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        var result = visitor.analyze(cu, Path.of("LeakyService.java"));

        assertEquals(1, result.leakFindings().size());
        ResourceLeakFinding finding = result.leakFindings().get(0);
        assertEquals(ResourceLeakFinding.LeakKind.RESOURCE_RESOLVER, finding.kind());
        assertEquals("resolver", finding.variableName());
        assertEquals("getServiceResourceResolver", finding.callName());
    }

    @Test
    void testDoesNotFlagTryWithResources() {
        String code = """
                package com.example;
                import org.apache.sling.api.resource.ResourceResolverFactory;
                import org.apache.sling.api.resource.ResourceResolver;
                public class SafeService {
                    ResourceResolverFactory factory;
                    public void doWork() throws Exception {
                        try (ResourceResolver resolver = factory.getServiceResourceResolver(null)) {
                            resolver.getResource("/content");
                        }
                    }
                }
                """;
        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        var result = visitor.analyze(cu, Path.of("SafeService.java"));

        assertTrue(result.leakFindings().isEmpty(), "Try-with-resources should not be flagged as a leak");
    }

    @Test
    void testDoesNotFlagProperlyClosedInFinally() {
        String code = """
                package com.example;
                import org.apache.sling.api.resource.ResourceResolverFactory;
                import org.apache.sling.api.resource.ResourceResolver;
                public class SafeService {
                    ResourceResolverFactory factory;
                    public void doWork() throws Exception {
                        ResourceResolver resolver = factory.getServiceResourceResolver(null);
                        try {
                            resolver.getResource("/content");
                        } finally {
                            if (resolver != null) {
                                resolver.close();
                            }
                        }
                    }
                }
                """;
        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        var result = visitor.analyze(cu, Path.of("SafeService.java"));

        assertTrue(result.leakFindings().isEmpty(), "Proper close in finally should not be flagged as a leak");
    }

    @Test
    void testDetectsUnclosedJcrSession() {
        String code = """
                package com.example;
                import org.apache.sling.jcr.api.SlingRepository;
                import javax.jcr.Session;
                public class LeakySessionService {
                    SlingRepository repository;
                    public void doWork() throws Exception {
                        Session session = repository.loginService("subservice", null);
                        session.getNode("/content");
                        // session is never logged out!
                    }
                }
                """;
        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        var result = visitor.analyze(cu, Path.of("LeakySessionService.java"));

        assertEquals(1, result.leakFindings().size());
        ResourceLeakFinding finding = result.leakFindings().get(0);
        assertEquals(ResourceLeakFinding.LeakKind.JCR_SESSION, finding.kind());
        assertEquals("session", finding.variableName());
    }

    @Test
    void testDetectsOakQueryTraversalRisk() {
        String code = """
                package com.example;
                import org.apache.sling.api.resource.ResourceResolver;
                public class TraversalService {
                    public void search(ResourceResolver resolver) {
                        resolver.findResources("SELECT * FROM [nt:base]", "JCR-SQL2");
                    }
                }
                """;
        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        var result = visitor.analyze(cu, Path.of("TraversalService.java"));

        assertEquals(1, result.traversalFindings().size());
        assertEquals("SELECT * FROM [nt:base]", result.traversalFindings().get(0).queryString());
    }

    @Test
    void testDoesNotFlagDirectlyReturnedResolver() {
        String code = """
                package com.example;
                import org.apache.sling.api.resource.ResourceResolverFactory;
                import org.apache.sling.api.resource.ResourceResolver;
                public class FactoryService {
                    ResourceResolverFactory factory;
                    public ResourceResolver getServiceResolver() throws Exception {
                        return factory.getServiceResourceResolver(null);
                    }
                }
                """;
        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        var result = visitor.analyze(cu, Path.of("FactoryService.java"));

        assertTrue(result.leakFindings().isEmpty(), "Factory method returning resolver directly should not be flagged as leak");
    }
}
