package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rule AEM-QUAL-002 — Implementation of @ProviderType Product API (Adobe Cloud Manager CQBP-84).
 *
 * <p>Flags custom classes implementing or extending product interfaces or classes annotated
 * with {@code @org.osgi.annotation.versioning.ProviderType}. Such APIs are maintained solely
 * by Adobe product engineering; custom implementations break during continuous Cloud Service updates.
 */
public final class AemQual002Rule implements RuleEvaluator {

    private static final Set<String> PROVIDER_TYPES = Set.of(
            "com.day.cq.wcm.api.Page",
            "com.day.cq.wcm.api.PageManager",
            "com.day.cq.wcm.api.Template",
            "com.day.cq.dam.api.Asset",
            "com.day.cq.dam.api.Rendition",
            "com.day.cq.dam.api.AssetManager",
            "org.apache.sling.api.resource.Resource",
            "org.apache.sling.api.resource.ResourceResolver",
            "org.apache.sling.api.resource.ResourceResolverFactory",
            "org.apache.sling.api.SlingHttpServletRequest",
            "org.apache.sling.api.SlingHttpServletResponse",
            "javax.jcr.Session",
            "javax.jcr.Node",
            "javax.jcr.Workspace",
            "javax.jcr.Repository",
            "javax.jcr.query.Query",
            "javax.jcr.query.QueryResult",
            "com.day.cq.search.Query",
            "com.day.cq.search.result.SearchResult",
            "com.day.cq.tagging.Tag",
            "com.day.cq.tagging.TagManager"
    );

    @Override
    public String getRuleId() {
        return RuleId.AEM_QUAL_002.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        Set<String> checkedFqns = new HashSet<>();

        List<JavaClassNode> allClasses = new ArrayList<>();
        allClasses.addAll(context.javaClasses());
        allClasses.addAll(context.components());

        for (JavaClassNode clazz : allClasses) {
            if (!checkedFqns.add(clazz.getFqn())) {
                continue;
            }

            for (String iface : clazz.getInterfaces()) {
                if (isProviderType(iface)) {
                    violations.add(createViolation(context, clazz, iface, "implements"));
                }
            }

            clazz.getSuperClass().ifPresent(superClass -> {
                if (isProviderType(superClass)) {
                    violations.add(createViolation(context, clazz, superClass, "extends"));
                }
            });
        }
        return violations;
    }

    private boolean isProviderType(String typeName) {
        if (PROVIDER_TYPES.contains(typeName)) {
            return true;
        }
        for (String pt : PROVIDER_TYPES) {
            if (pt.endsWith("." + typeName)) {
                return true;
            }
        }
        return false;
    }

    private Violation createViolation(RuleContext context, JavaClassNode clazz, String providerType, String relation) {
        String relPath = relativePath(context, clazz.getFilePath());
        int line = clazz.getSourceLocation() != null ? clazz.getSourceLocation().line() : 1;
        return new Violation(
                RuleId.AEM_QUAL_002.id(),
                RuleId.AEM_QUAL_002.defaultLevel(),
                "PROVIDER_TYPE_IMPLEMENTATION",
                String.format("Class '%s' %s product API '%s' which is annotated with @ProviderType. Adobe Cloud Manager rule CQBP-84 forbids custom implementations.",
                        clazz.getFqn(), relation, providerType),
                relPath,
                new TextRegion(line, 1, line, 1),
                providerType,
                "Do not implement product @ProviderType interfaces. Consume the API via composition, adaptTo, or request attributes.",
                List.of()
        );
    }

    private String relativePath(RuleContext context, Path path) {
        return context.relativePath(path);
    }
}
