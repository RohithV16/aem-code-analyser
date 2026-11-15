package io.github.rohithv16.aem.analyser.core.parser.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdministrativeCallDetectionTest {

    @TempDir
    Path root;

    private JavaSourceParseResult parse(String source) throws Exception {
        Path file = root.resolve("Service.java");
        Files.writeString(file, source);
        return new JavaSourceParser().parse(file);
    }

    @Test
    void genericRepositoryLoginWithAdminCredentialsIsDetected() throws Exception {
        JavaSourceParseResult result = parse("""
                package com.example;
                import javax.jcr.Session;
                import javax.jcr.SimpleCredentials;
                public class Service {
                    void run(org.apache.jackrabbit.api.JackrabbitRepository repository) throws Exception {
                        Session session = repository.login(
                            new SimpleCredentials("admin", "admin".toCharArray()));
                    }
                }
                """);

        assertTrue(result.administrativeCalls().stream()
                        .anyMatch(call -> call.methodName().equals("login")),
                () -> "Admin credentials on login() must be detected: " + result.administrativeCalls());
    }

    @Test
    void genericResolverMapWithAdminUserIsDetected() throws Exception {
        JavaSourceParseResult result = parse("""
                package com.example;
                import org.apache.sling.api.resource.ResourceResolverFactory;
                import java.util.Map;
                public class Service {
                    ResourceResolverFactory factory;
                    void run() throws Exception {
                        factory.getResourceResolver(Map.of(ResourceResolverFactory.USER, "admin"));
                    }
                }
                """);

        assertTrue(!result.administrativeCalls().isEmpty(),
                () -> "Admin user in an auth map must be detected: " + result.administrativeCalls());
    }
}
