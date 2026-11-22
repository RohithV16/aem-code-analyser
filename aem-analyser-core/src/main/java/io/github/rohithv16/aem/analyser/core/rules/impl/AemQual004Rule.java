package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rule AEM-QUAL-004 — Unsafe Mutable State in OSGi Component (Cloud Manager CQBP-ThreadSafety / WTTech AEM-3).
 *
 * <p>Flags OSGi {@code @Component} or servlet classes that store non-thread-safe or session-bound
 * instances (such as {@code ResourceResolver}, {@code Session}, {@code PageManager}, {@code AssetManager},
 * {@code Node}, {@code QueryBuilder}) in class fields. OSGi components are singletons by default;
 * storing request or session context in fields causes race conditions, data leaks, and memory exhaustion.
 *
 * <p>Field types reach this rule already resolved to fully-qualified names ({@code OsgiComponentVisitor} →
 * {@code AnnotationUtils.resolveTypeName}). Matching therefore uses exact FQNs plus a product-package
 * prefix test, so a project type that merely shares a simple name ({@code com.acme.core.models.Node})
 * is not reported. Bare simple names only apply to genuinely unresolved types (default-package sources).
 */
public final class AemQual004Rule implements RuleEvaluator {

    /** Thread-unsafe product types, matched exactly. */
    private static final Set<String> UNSAFE_FIELD_TYPE_FQNS = Set.of(
            "org.apache.sling.api.resource.ResourceResolver",
            "javax.jcr.Session",
            "com.day.cq.wcm.api.PageManager",
            "com.day.cq.dam.api.AssetManager",
            "javax.jcr.Node",
            "com.day.cq.search.QueryBuilder",
            "com.day.cq.tagging.TagManager",
            "org.apache.sling.api.SlingHttpServletRequest",
            "org.apache.sling.api.SlingHttpServletResponse"
    );

    /**
     * Simple names of the same types. Applied only when the field type could not be qualified, or when a
     * type in a product package carries the name (product implementations such as {@code ...PageManagerImpl}).
     */
    private static final Set<String> UNSAFE_FIELD_TYPE_NAMES = Set.of(
            "ResourceResolver",
            "Session",
            "PageManager",
            "AssetManager",
            "Node",
            "QueryBuilder",
            "TagManager",
            "SlingHttpServletRequest",
            "SlingHttpServletResponse"
    );

    /** Packages owned by Adobe/the platform; a type here is a product type rather than project code. */
    private static final List<String> PRODUCT_PACKAGE_PREFIXES = List.of(
            "com.adobe.cq.",
            "com.day.cq.",
            "org.apache.sling.",
            "javax.jcr.",
            "javax.servlet."
    );

    @Override
    public String getRuleId() {
        return RuleId.AEM_QUAL_004.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        Set<String> projectTypes = projectTypes(context);
        for (OSGiComponentNode component : context.components()) {
            for (OSGiComponentNode.DeclaredField field : component.getDeclaredFields()) {
                if (isUnsafeType(field.typeName(), projectTypes)) {
                    String relPath = relativePath(context, component.getFilePath());
                    int line = field.location() != null ? field.location().line() :
                            (component.getSourceLocation() != null ? component.getSourceLocation().line() : 1);

                    violations.add(new Violation(
                            RuleId.AEM_QUAL_004.id(),
                            RuleId.AEM_QUAL_004.defaultLevel(),
                            "UNSAFE_COMPONENT_STATE",
                            String.format("OSGi component '%s' declares non-thread-safe field '%s' of type '%s'. OSGi components are singletons; storing session or request objects as fields causes race conditions and memory leaks.",
                                    component.getComponentClassFqn(), field.name(), field.typeName()),
                            relPath,
                            new TextRegion(line, 1, line, 1),
                            field.name(),
                            String.format("Obtain '%s' as a local variable within method execution (e.g. via request.getResourceResolver() or try-with-resources) and do not store it as a class field.", field.typeName()),
                            List.of()
                    ));
                }
            }
        }
        return violations;
    }

    private boolean isUnsafeType(String typeName, Set<String> projectTypes) {
        if (typeName == null || typeName.isBlank()) {
            return false;
        }
        if (UNSAFE_FIELD_TYPE_FQNS.contains(typeName)) {
            return true;
        }
        if (!UNSAFE_FIELD_TYPE_NAMES.contains(simpleName(typeName))) {
            return false;
        }
        int lastDot = typeName.lastIndexOf('.');
        if (lastDot < 0) {
            // Unresolved simple name: nothing else to go on, so trust the unsafe simple name.
            return true;
        }
        // A type the project declares itself is project code, whatever it is called.
        if (projectTypes.contains(typeName)) {
            return false;
        }
        // Qualified but unknown to the project index: only a product-package type is a product type.
        return isProductPackage(typeName);
    }

    private static String simpleName(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        return lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
    }

    private static boolean isProductPackage(String typeName) {
        for (String prefix : PRODUCT_PACKAGE_PREFIXES) {
            if (typeName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every type the analysed project declares, across all its modules, so a project class can never be
     * mistaken for a product type just because it shares a simple name.
     */
    private static Set<String> projectTypes(RuleContext context) {
        Set<String> types = new HashSet<>();
        if (context.buildResult() == null) {
            return types;
        }
        addKeys(types, context.buildResult().componentsByFqn());
        addKeys(types, context.buildResult().classNodes());
        addKeys(types, context.buildResult().modelNodes());
        return types;
    }

    private static void addKeys(Set<String> target, Map<String, ? extends JavaClassNode> nodes) {
        if (nodes != null) {
            target.addAll(nodes.keySet());
        }
    }

    private String relativePath(RuleContext context, Path path) {
        return context.relativePath(path);
    }
}
