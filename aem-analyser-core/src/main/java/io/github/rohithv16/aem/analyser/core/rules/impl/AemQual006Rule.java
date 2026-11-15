package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.util.ArrayList;
import java.util.List;

/**
 * AEM-QUAL-006: request-scoped ResourceResolver/Session stored in a component instance field.
 */
public final class AemQual006Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_QUAL_006.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (OSGiComponentNode component : context.components()) {
            for (OSGiComponentNode.DeclaredField field : component.getDeclaredFields()) {
                if (field.isStatic() || field.typeName() == null) {
                    continue;
                }
                String type = field.typeName();
                if (!type.endsWith("ResourceResolver") && !type.endsWith("Session")) {
                    continue;
                }
                var location = field.location();
                TextRegion region = location == null ? TextRegion.forWholeFile()
                        : new TextRegion(location.line(), location.column(), location.line(),
                        location.column() + field.name().length());
                violations.add(new Violation(
                        RuleId.AEM_QUAL_006.id(),
                        RuleId.AEM_QUAL_006.defaultLevel(),
                        "REQUEST_SCOPED_FIELD",
                        "OSGi component '" + component.getComponentClassFqn() + "' stores '" + field.name()
                                + "' of type '" + type + "' in an instance field. The resolver/session may belong"
                                + " to another request and must not be reused across calls.",
                        relativePath(context, component.getFilePath()),
                        region,
                        component.getComponentClassFqn(),
                        "Obtain the resolver/session per request and close it in a finally block or"
                                + " try-with-resources; do not cache it in a component field.",
                        List.of()));
            }
        }
        return violations;
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        if (path == null) {
            return "";
        }
        return context.projectRoot().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
