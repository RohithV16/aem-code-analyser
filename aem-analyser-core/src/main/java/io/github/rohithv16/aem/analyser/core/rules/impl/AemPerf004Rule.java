package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AEM-PERF-004: JCR/Oak query without a limit.
 */
public final class AemPerf004Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_PERF_004.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (var classNode : context.buildResult().classNodes().values()) {
            var file = classNode.getFilePath();
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String lower = line.toLowerCase(Locale.ROOT);
                    boolean queryCall = lower.contains("findresources(") || lower.contains("createquery(");
                    boolean hasQueryLiteral = lower.contains("\"select") || lower.contains("\"xpath")
                            || lower.contains("\"//");
                    if (queryCall && hasQueryLiteral && !lower.contains("limit")) {
                        violations.add(new Violation(
                                RuleId.AEM_PERF_004.id(),
                                RuleId.AEM_PERF_004.defaultLevel(),
                                "UNBOUNDED_QUERY",
                                "JCR/Oak query has no limit and may scan an unbounded result set.",
                                context.projectRoot().relativize(file.toAbsolutePath().normalize())
                                        .toString().replace('\\', '/'),
                                new TextRegion(i + 1, 1, i + 1, 2),
                                null,
                                "Add a result limit (p.limit or a query LIMIT clause) and restrict the path/nodetype.",
                                List.of()));
                    }
                }
            } catch (IOException ignored) {
                // unreadable file: diagnostics handled by the parser
            }
        }
        return violations;
    }
}
