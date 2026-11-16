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
 * Rule AEM-PERF-003: Oak Query Traversal Risk.
 * Flags repository queries querying [nt:base] without indexed property filters or path scoping.
 */
public final class AemPerf003Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_PERF_003.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        if (context.queryTraversals() == null) {
            return violations;
        }

        for (QueryTraversalFinding finding : context.queryTraversals()) {
            String queryText = finding.queryString() == null ? "" : finding.queryString().toLowerCase(Locale.ROOT);
            // The parser records every literal query call site; this rule only owns the unbounded
            // traversal shape (nt:base without a WHERE clause).
            if (!queryText.contains("nt:base") || queryText.contains("where")) {
                continue;
            }
            TextRegion region = new TextRegion(finding.line(), finding.column(), finding.line(), finding.column() + 30);
            violations.add(new Violation(
                    RuleId.AEM_PERF_003.id(),
                    RuleId.AEM_PERF_003.defaultLevel(),
                    "OAK_QUERY_TRAVERSAL",
                    "Query '" + finding.queryString() + "' queries [nt:base] without index constraint or WHERE clause, risking Oak traversal errors.",
                    relativePath(context, finding.filePath()),
                    region,
                    null,
                    "Constrain the query with a specific nodetype (e.g. cq:Page, dam:Asset) and add a path scope or indexed property filter.",
                    List.of()
            ));
        }

        return violations;
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        return context.relativePath(path);
    }
}
