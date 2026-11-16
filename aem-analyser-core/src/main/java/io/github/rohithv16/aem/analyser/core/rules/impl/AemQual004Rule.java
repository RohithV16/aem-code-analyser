package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Rule AEM-QUAL-004 — Unsafe Mutable State in OSGi Component (Cloud Manager CQBP-ThreadSafety / WTTech AEM-3).
 *
 * <p>Flags OSGi {@code @Component} or servlet classes that store non-thread-safe or session-bound
 * instances (such as {@code ResourceResolver}, {@code Session}, {@code PageManager}, {@code AssetManager},
 * {@code Node}, {@code QueryBuilder}) in class fields. OSGi components are singletons by default;
 * storing request or session context in fields causes race conditions, data leaks, and memory exhaustion.
 */
public final class AemQual004Rule implements RuleEvaluator {

    private static final Set<String> UNSAFE_FIELD_TYPES = Set.of(
            "org.apache.sling.api.resource.ResourceResolver",
            "ResourceResolver",
            "javax.jcr.Session",
            "Session",
            "com.day.cq.wcm.api.PageManager",
            "PageManager",
            "com.day.cq.dam.api.AssetManager",
            "AssetManager",
            "javax.jcr.Node",
            "Node",
            "com.day.cq.search.QueryBuilder",
            "QueryBuilder",
            "com.day.cq.tagging.TagManager",
            "TagManager",
            "org.apache.sling.api.SlingHttpServletRequest",
            "SlingHttpServletRequest",
            "org.apache.sling.api.SlingHttpServletResponse",
            "SlingHttpServletResponse"
    );

    @Override
    public String getRuleId() {
        return RuleId.AEM_QUAL_004.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (OSGiComponentNode component : context.components()) {
            for (OSGiComponentNode.DeclaredField field : component.getDeclaredFields()) {
                if (isUnsafeType(field.typeName())) {
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

    private boolean isUnsafeType(String typeName) {
        if (typeName == null) {
            return false;
        }
        if (UNSAFE_FIELD_TYPES.contains(typeName)) {
            return true;
        }
        for (String unsafe : UNSAFE_FIELD_TYPES) {
            if (typeName.endsWith("." + unsafe)) {
                return true;
            }
        }
        return false;
    }

    private String relativePath(RuleContext context, Path path) {
        if (path == null) {
            return "";
        }
        try {
            return context.projectRoot().relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }
}
