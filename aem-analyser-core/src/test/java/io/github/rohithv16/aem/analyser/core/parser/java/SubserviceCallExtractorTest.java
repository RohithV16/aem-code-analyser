package io.github.rohithv16.aem.analyser.core.parser.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall;
import io.github.rohithv16.aem.analyser.core.parser.java.visitor.SubserviceCallExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SubserviceCallExtractorTest {

    private final JavaParser parser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    private final SubserviceCallExtractor extractor = new SubserviceCallExtractor();

    @Test
    void testExtractsSubserviceFromSingletonMapWithConstant() {
        String code = """
                package com.example;
                import org.apache.sling.api.resource.ResourceResolverFactory;
                import org.apache.sling.api.resource.ResourceResolver;
                import java.util.Collections;
                import java.util.Map;
                public class MyConsumer {
                    private static final String SUBSERVICE_NAME = "content-service";
                    ResourceResolverFactory resourceResolverFactory;
                    private ResourceResolver getServiceResolver() throws Exception {
                        Map<String, Object> authInfo = Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME);
                        return resourceResolverFactory.getServiceResourceResolver(authInfo);
                    }
                }
                """;
        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        List<SubserviceCall> calls = extractor.extract(cu, "com.example.MyConsumer", Path.of("MyConsumer.java"));

        assertFalse(calls.isEmpty());
        assertEquals("content-service", calls.get(0).subserviceName(),
                "Subservice should resolve to constant value 'content-service', not key constant name 'SUBSERVICE'");
    }

    @Test
    void testResolvesFieldAccessConstantFromSameCompilationUnit() {
        String code = """
                package com.example;
                import org.apache.sling.api.resource.ResourceResolverFactory;
                import org.apache.sling.api.resource.ResourceResolver;
                import java.util.Collections;
                import java.util.Map;
                class ProjectConstants {
                    static final String IMPORT_SERVICE_SUBSERVICE_NAME = "import-service";
                }
                public class MyConsumer {
                    ResourceResolverFactory resourceResolverFactory;
                    private ResourceResolver getServiceResolver() throws Exception {
                        Map<String, Object> authInfo = Collections.singletonMap(
                                ResourceResolverFactory.SUBSERVICE, ProjectConstants.IMPORT_SERVICE_SUBSERVICE_NAME);
                        return resourceResolverFactory.getServiceResourceResolver(authInfo);
                    }
                }
                """;
        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        List<SubserviceCall> calls = extractor.extract(cu, "com.example.MyConsumer", Path.of("MyConsumer.java"));

        assertFalse(calls.isEmpty());
        assertEquals("import-service", calls.get(0).subserviceName());
    }

    @Test
    void genericFactoryGetResourceResolverWithMapIsDetected() {
        String code = """
                package com.example;
                import org.apache.sling.api.resource.ResourceResolverFactory;
                import org.apache.sling.api.resource.ResourceResolver;
                import java.util.Collections;
                import java.util.Map;
                public class MyConsumer {
                    private ResourceResolverFactory resourceResolverFactory;
                    private ResourceResolver getResolver() throws Exception {
                        Map<String, Object> authInfo = Collections.singletonMap(
                                ResourceResolverFactory.SUBSERVICE, "generic-svc");
                        return resourceResolverFactory.getResourceResolver(authInfo);
                    }
                }
                """;
        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        List<SubserviceCall> calls = extractor.extract(cu, "com.example.MyConsumer", Path.of("MyConsumer.java"));

        assertEquals(1, calls.size());
        assertEquals("generic-svc", calls.get(0).subserviceName());
        assertEquals(SubserviceCall.Resolution.LITERAL, calls.get(0).resolution());
    }

    @Test
    void unresolvedMapVariableIsMarkedUnresolvedNotNull() {
        String code = """
                package com.example;
                import org.apache.sling.api.resource.ResourceResolverFactory;
                import org.apache.sling.api.resource.ResourceResolver;
                import java.util.Map;
                public class MyConsumer {
                    ResourceResolverFactory resourceResolverFactory;
                    ResourceResolver getServiceResolver(Map<String, Object> param) throws Exception {
                        return resourceResolverFactory.getServiceResourceResolver(param);
                    }
                }
                """;
        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        List<SubserviceCall> calls = extractor.extract(cu, "com.example.MyConsumer", Path.of("MyConsumer.java"));

        assertEquals(1, calls.size());
        assertEquals(SubserviceCall.Resolution.UNRESOLVED, calls.get(0).resolution(),
                "An unevaluable variable must not be treated as an explicit null subservice");
    }
}
