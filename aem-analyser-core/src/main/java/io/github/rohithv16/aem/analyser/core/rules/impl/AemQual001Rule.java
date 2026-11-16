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

/**
 * Rule AEM-QUAL-001 — Path-Bound Sling Servlet Registration (Adobe Cloud Manager CQBP-75).
 *
 * <p>Flags servlets registered using absolute paths via {@code sling.servlet.paths}.
 * Best practice dictates registering servlets using {@code sling.servlet.resourceTypes}
 * to ensure proper JCR access control (ACL) enforcement, vanity URL support, and caching.
 */
public final class AemQual001Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_QUAL_001.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (OSGiComponentNode component : context.components()) {
            for (String prop : component.getComponentProperties()) {
                String lower = prop.toLowerCase().strip();
                if (lower.startsWith("sling.servlet.paths=") || lower.startsWith("sling.servlet.paths")) {
                    String pathValue = prop.contains("=") ? prop.substring(prop.indexOf('=') + 1).strip() : prop;
                    String relPath = relativePath(context, component.getFilePath());
                    int line = component.getSourceLocation() != null ? component.getSourceLocation().line() : 1;
                    violations.add(new Violation(
                            RuleId.AEM_QUAL_001.id(),
                            RuleId.AEM_QUAL_001.defaultLevel(),
                            "PATH_BOUND_SERVLET",
                            String.format("Servlet '%s' is registered with absolute path(s) '%s'. Cloud Manager rule CQBP-75 requires registering servlets by resourceType ('sling.servlet.resourceTypes').",
                                    component.getComponentClassFqn(), pathValue),
                            relPath,
                            new TextRegion(line, 1, line, 1),
                            pathValue,
                            "Bind servlet using 'sling.servlet.resourceTypes' instead of 'sling.servlet.paths' to ensure JCR ACL enforcement, vanity path resolution, and cacheability.",
                            List.of()
                    ));
                }
            }
        }
        return violations;
    }

    private String relativePath(RuleContext context, Path path) {
        return context.relativePath(path);
    }
}
