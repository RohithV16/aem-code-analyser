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
                    private static final String SUBSERVICE_NAME = "contentsyncbulkreplication-svc";
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
        assertEquals("contentsyncbulkreplication-svc", calls.get(0).subserviceName(),
                "Subservice should resolve to constant value 'contentsyncbulkreplication-svc', not key constant name 'SUBSERVICE'");
    }
}
