package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.util.List;

/**
 * Contract implemented by each Phase 1 detection rule.
 */
public interface RuleEvaluator {

    String getRuleId();

    List<Violation> evaluate(RuleContext context);
}
