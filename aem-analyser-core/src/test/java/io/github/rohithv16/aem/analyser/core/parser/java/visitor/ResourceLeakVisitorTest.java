package io.github.rohithv16.aem.analyser.core.parser.java.visitor;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import io.github.rohithv16.aem.analyser.core.model.perf.ResourceLeakFinding;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceLeakVisitorTest {

    private final Path file = Path.of("/mock/project/Service.java");

    private List<ResourceLeakFinding> leaks(String body) {
        CompilationUnit cu = StaticJavaParser.parse("class Service { void run() throws Exception { " + body + " } }");
        return new ResourceLeakVisitor().analyze(cu, file).leakFindings();
    }

    @Test
    void borrowedResolverFromAssetIsNotALeak() {
        List<ResourceLeakFinding> findings = leaks(
                "ResourceResolver resolver = asset.getResourceResolver(); resolver.commit();");

        assertTrue(findings.isEmpty(), () -> "Borrowed resolver must not be flagged: " + findings);
    }

    @Test
    void unclosedFactoryResolverIsALeak() {
        List<ResourceLeakFinding> findings = leaks(
                "ResourceResolver resolver = factory.getServiceResourceResolver(null); resolver.commit();");

        assertEquals(1, findings.size());
        assertEquals(ResourceLeakFinding.LeakKind.RESOURCE_RESOLVER, findings.get(0).kind());
    }

    @Test
    void sessionAdaptedFromTryWithResourcesResolverIsNotALeak() {
        List<ResourceLeakFinding> findings = leaks(
                "try (ResourceResolver resolver = factory.getServiceResourceResolver(null)) {"
                        + " Session session = resolver.adaptTo(Session.class); session.save(); }");

        assertTrue(findings.isEmpty(), () -> "Session owned by a managed resolver must not be flagged: " + findings);
    }

    @Test
    void sessionAdaptedFromUnmanagedResolverIsALeak() {
        List<ResourceLeakFinding> findings = leaks(
                "ResourceResolver resolver = factory.getServiceResourceResolver(null);"
                        + " Session session = resolver.adaptTo(Session.class); session.save();");

        assertTrue(findings.stream().anyMatch(f -> f.kind() == ResourceLeakFinding.LeakKind.JCR_SESSION),
                () -> "Adapted session without an owning close must be flagged: " + findings);
    }

    @Test
    void sessionAdaptedFromRequestResolverIsNotALeak() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class Service {
                    void run(SlingHttpServletRequest req) throws Exception {
                        ResourceResolver resolver = req.getResourceResolver();
                        Session session = resolver.adaptTo(Session.class);
                        session.save();
                    }
                }
                """);

        List<ResourceLeakFinding> findings = new ResourceLeakVisitor().analyze(cu, file).leakFindings();

        assertTrue(findings.stream().noneMatch(f -> f.kind() == ResourceLeakFinding.LeakKind.JCR_SESSION),
                () -> "A request-owned resolver owns the session: " + findings);
    }

    @Test
    void sessionAdaptedFromLocalFactoryResolverIsStillALeak() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class Service {
                    ResourceResolverFactory factory;
                    void run() throws Exception {
                        ResourceResolver resolver = factory.getServiceResourceResolver(null);
                        Session session = resolver.adaptTo(Session.class);
                        session.save();
                    }
                }
                """);

        List<ResourceLeakFinding> findings = new ResourceLeakVisitor().analyze(cu, file).leakFindings();

        assertTrue(findings.stream().anyMatch(f -> f.kind() == ResourceLeakFinding.LeakKind.JCR_SESSION),
                () -> "A factory resolver is owned by the method: " + findings);
    }

    @Test
    void sessionAdaptedFromBorrowedParameterResolverIsNotALeak() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class Service {
                    void run(ResourceResolver resolver) throws Exception {
                        Session session = resolver.adaptTo(Session.class);
                        session.save();
                    }
                }
                """);

        List<ResourceLeakFinding> findings = new ResourceLeakVisitor().analyze(cu, file).leakFindings();

        assertTrue(findings.stream().noneMatch(f -> f.kind() == ResourceLeakFinding.LeakKind.JCR_SESSION),
                () -> "A caller-owned resolver owns the session: " + findings);
    }

    @Test
    void sessionAdaptedFromInjectedFieldResolverIsNotALeak() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class Service {
                    private ResourceResolver resolver;
                    void run() throws Exception {
                        Session session = resolver.adaptTo(Session.class);
                        session.save();
                    }
                }
                """);

        List<ResourceLeakFinding> findings = new ResourceLeakVisitor().analyze(cu, file).leakFindings();

        assertTrue(findings.stream().noneMatch(f -> f.kind() == ResourceLeakFinding.LeakKind.JCR_SESSION),
                () -> "An injected field resolver owns the session: " + findings);
    }

    @Test
    void factoryResolverIsDetectedEvenWhenVariableIsNotNamedFactory() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class Service {
                    private ResourceResolverFactory rrf;
                    void run() throws Exception {
                        ResourceResolver resolver = rrf.getResourceResolver(null);
                        resolver.commit();
                    }
                }
                """);

        List<ResourceLeakFinding> findings = new ResourceLeakVisitor().analyze(cu, file).leakFindings();

        assertTrue(findings.stream().anyMatch(f -> f.kind() == ResourceLeakFinding.LeakKind.RESOURCE_RESOLVER),
                () -> "Receiver type must decide ownership, not its name: " + findings);
    }

    @Test
    void loginOnUnrelatedServiceIsNotTreatedAsJcrLogin() {
        CompilationUnit cu = StaticJavaParser.parse("""
                class Service {
                    void run() throws Exception {
                        Session session = someService.login("user");
                        session.save();
                    }
                }
                """);

        List<ResourceLeakFinding> findings = new ResourceLeakVisitor().analyze(cu, file).leakFindings();

        assertTrue(findings.stream().noneMatch(f -> f.kind() == ResourceLeakFinding.LeakKind.JCR_SESSION),
                () -> "Only repository/session login calls create JCR sessions: " + findings);
    }
}
