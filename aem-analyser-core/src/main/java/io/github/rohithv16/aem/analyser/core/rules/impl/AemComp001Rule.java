package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.htl.CqComponentDefinition;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule AEM-COMP-001: Deprecated Classic UI Dialog.
 * Flags presence of legacy dialog.xml which is deprecated and unsupported in modern AEM Touch UI / Cloud Service.
 */
public final class AemComp001Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_COMP_001.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        if (context.cqComponents() == null || context.cqComponents().isEmpty()) {
            return violations;
        }

        for (CqComponentDefinition comp : context.cqComponents()) {
            if (comp.hasClassicUiDialog() && comp.classicUiDialogXml() != null) {
                violations.add(new Violation(
                        RuleId.AEM_COMP_001.id(),
                        RuleId.AEM_COMP_001.defaultLevel(),
                        "DEPRECATED_CLASSIC_UI_DIALOG",
                        "Component '" + comp.resourceType() + "' defines legacy Classic UI dialog.xml.",
                        relativePath(context, comp.classicUiDialogXml()),
                        TextRegion.forWholeFile(),
                        null,
                        "Migrate dialog.xml to Granite/Coral Touch UI dialog at _cq_dialog/.content.xml.",
                        List.of()
                ));
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
