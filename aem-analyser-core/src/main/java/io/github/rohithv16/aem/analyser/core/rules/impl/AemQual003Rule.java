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
 * Rule AEM-QUAL-003 — Whiteboard Sling Scheduler Usage (Adobe Cloud Manager AMSCORE-554).
 *
 * <p>Flags OSGi components registering as whiteboard schedulers via {@code scheduler.expression}
 * or {@code scheduler.period}. In AEM as a Cloud Service, whiteboard schedulers do not guarantee
 * single execution across dynamically scaling author/publish pod clusters.
 */
public final class AemQual003Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_QUAL_003.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (OSGiComponentNode component : context.components()) {
            boolean hasSchedulerProp = false;
            String matchingProp = null;
            for (String prop : component.getComponentProperties()) {
                String lower = prop.toLowerCase().strip();
                if (lower.startsWith("scheduler.expression=") || lower.startsWith("scheduler.expression")
                        || lower.startsWith("scheduler.period=") || lower.startsWith("scheduler.period")) {
                    hasSchedulerProp = true;
                    matchingProp = prop;
                    break;
                }
            }

            boolean implementsSchedulerOptions = component.getServiceInterfaces().stream()
                    .anyMatch(i -> i.endsWith("ScheduleOptions") || i.equals("org.apache.sling.commons.scheduler.ScheduleOptions"));

            if (hasSchedulerProp || implementsSchedulerOptions) {
                String detail = matchingProp != null ? matchingProp : "ScheduleOptions";
                String relPath = relativePath(context, component.getFilePath());
                int line = component.getSourceLocation() != null ? component.getSourceLocation().line() : 1;
                violations.add(new Violation(
                        RuleId.AEM_QUAL_003.id(),
                        RuleId.AEM_QUAL_003.defaultLevel(),
                        "SLING_SCHEDULER_USAGE",
                        String.format("OSGi component '%s' registers a whiteboard Sling scheduler ('%s'). In AEM as a Cloud Service, whiteboard schedulers do not guarantee execution across auto-scaling pod clusters (AMSCORE-554).",
                                component.getComponentClassFqn(), detail),
                        relPath,
                        new TextRegion(line, 1, line, 1),
                        detail,
                        "Refactor to use Sling Scheduled Jobs (org.apache.sling.event.jobs.JobManager) which provide cluster-aware distributed execution locks.",
                        List.of()
                ));
            }
        }
        return violations;
    }

    private String relativePath(RuleContext context, Path path) {
        if (path == null) {
            return "";
        }
        try {
            return context.projectRoot().relativize(path).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }
}
