package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.perf.QueryTraversalFinding;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AEM-PERF-004: JCR/Oak query without a result limit.
 *
 * <p>Uses the AST-derived query call sites recorded by the parser, so the check sees multi-line
 * {@code createQuery}/{@code findResources} calls, ignores commented-out code and log strings, and
 * evaluates the query literal itself instead of the physical source line.
 */
public final class AemPerf004Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_PERF_004.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        if (context.queryTraversals() == null) {
            return violations;
        }
        for (QueryTraversalFinding finding : context.queryTraversals()) {
            String query = finding.queryString() == null ? "" : finding.queryString().toLowerCase(Locale.ROOT);
            if (query.contains("limit") || query.contains("maxresults")) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_PERF_004.id(),
                    RuleId.AEM_PERF_004.defaultLevel(),
                    "UNBOUNDED_QUERY",
                    "JCR/Oak query has no result limit and may scan an unbounded result set.",
                    relativePath(context, finding.filePath()),
                    new TextRegion(finding.line(), finding.column(),
                            finding.line(), finding.column() + 30),
                    null,
                    "Add a result limit (p.limit or a query LIMIT clause) and restrict the path/nodetype.",
                    List.of()));
        }
        return violations;
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        return context.relativePath(path);
    }
}
