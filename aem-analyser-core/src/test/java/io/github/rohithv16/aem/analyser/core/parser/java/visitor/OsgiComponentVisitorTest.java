package io.github.rohithv16.aem.analyser.core.parser.java.visitor;

import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.parser.java.JavaSourceParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsgiComponentVisitorTest {

    @TempDir
    Path root;

    private OSGiComponentNode component(String name, String source) throws Exception {
        Path file = root.resolve(name + ".java");
        Files.writeString(file, source);
        return new JavaSourceParser().parse(file).components().stream()
                .filter(c -> c.getComponentClassFqn().endsWith(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Component not parsed: " + name));
    }

    @Test
    void explicitEmptyServiceRegistersNoInterface() throws Exception {
        OSGiComponentNode component = component("NoService", """
                package com.example;
                import org.osgi.service.component.annotations.Component;
                @Component(service = {})
                public class NoService implements Runnable {
                    public void run() { }
                }
                """);

        assertTrue(component.getServiceInterfaces().isEmpty(),
                () -> "service={} must not inherit implemented interfaces: " + component.getServiceInterfaces());
    }

    @Test
    void absentServiceFallsBackToImplementedInterfaces() throws Exception {
        OSGiComponentNode component = component("ImplicitService", """
                package com.example;
                import org.osgi.service.component.annotations.Component;
                @Component
                public class ImplicitService implements Runnable {
                    public void run() { }
                }
                """);

        assertTrue(component.getServiceInterfaces().stream().anyMatch(s -> s.endsWith("Runnable")),
                () -> "Implicit service should use implemented interfaces: " + component.getServiceInterfaces());
    }

    @Test
    void explicitServiceOverridesImplementedInterfaces() throws Exception {
        OSGiComponentNode component = component("ExplicitService", """
                package com.example;
                import org.osgi.service.component.annotations.Component;
                @Component(service = CharSequence.class)
                public class ExplicitService implements Runnable {
                    public void run() { }
                }
                """);

        assertEquals(1, component.getServiceInterfaces().size(), component.getServiceInterfaces()::toString);
        assertTrue(component.getServiceInterfaces().iterator().next().endsWith("CharSequence"));
        assertTrue(component.getInterfaces().stream().anyMatch(s -> s.endsWith("Runnable")),
                () -> "Implemented interfaces must still be recorded for entry-point detection: "
                        + component.getInterfaces());
    }

    @Test
    void bindMethodAndConstructorReferencesAreCaptured() throws Exception {
        OSGiComponentNode component = component("Consumer", """
                package com.example;
                import org.osgi.service.component.annotations.Activate;
                import org.osgi.service.component.annotations.Component;
                import org.osgi.service.component.annotations.Reference;
                @Component
                public class Consumer {
                    @Activate
                    public Consumer(@Reference CharSequence constructorService) { }
                    @Reference
                    protected void bind(Number bindService) { }
                }
                """);

        assertTrue(component.getReferences().containsKey("bind"), component.getReferences()::toString);
        assertTrue(component.getReferences().get("bind").endsWith("Number"),
                component.getReferences()::toString);
        assertTrue(component.getReferences().containsKey("constructorService"),
                component.getReferences()::toString);
    }
}
