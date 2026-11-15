package io.github.aemtools.analyser.core.rules;

import io.github.aemtools.analyser.core.model.report.Violation;

import java.util.List;

/**
 * Contract implemented by each Phase 1 detection rule.
 */
public interface RuleEvaluator {

    String getRuleId();

    List<Violation> evaluate(RuleContext context);
}
