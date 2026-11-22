package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.vault.WorkflowContentParser;
import io.github.rohithv16.aem.analyser.core.parser.vault.WorkflowContentParser.Launcher;
import io.github.rohithv16.aem.analyser.core.parser.vault.WorkflowContentParser.ProcessStep;
import io.github.rohithv16.aem.analyser.core.parser.vault.WorkflowContentParser.WorkflowModel;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AEM-WF-001: workflow launchers and models that resolve to nothing.
 *
 * <p>A launcher names its model by path and a PROCESS step names its class by fully-qualified name;
 * both are plain strings in content. Rename or delete either and AEM logs nothing at the launcher:
 * the workflow simply never starts, and the symptom ("our asset workflow stopped weeks ago") is
 * discovered long after the release that caused it. Cloud Manager and the AEM Analyzer do not read
 * workflow content, so nothing else closes this loop.
 *
 * <p>Both checks are ownership-scoped, which is what keeps the rule quiet on real projects:
 *
 * <ul>
 *   <li>a model reference that resolves into a platform namespace ({@code /var/workflow/models/dam/…})
 *       or names a known out-of-the-box model is platform content, not a missing project model;</li>
 *   <li>a PROCESS class is only checked when its package is one the project's own sources declare,
 *       so a class supplied by an ACS Commons or vendor bundle can never be reported — and a
 *       project without Java sources is never judged at all.</li>
 * </ul>
 *
 * <p>A glob that matches no authored content is deliberately not modelled: content is authored on
 * the environment, so the project's package cannot prove a glob wrong.
 */
public final class AemWf001Rule implements RuleEvaluator {

    /** Roots a launcher's {@code cq:workflowModel} can point into. */
    private static final List<String> MODEL_ROOTS = List.of(
            "/var/workflow/models/",
            "/etc/workflow/models/",
            "/conf/global/settings/workflow/models/");

    /** First path segment of an out-of-the-box model tree; the product ships these, not the project. */
    private static final Set<String> PLATFORM_MODEL_NAMESPACES = Set.of(
            "dam", "wcm", "commerce", "projects", "translation", "versioning", "forms", "screens",
            "assets", "workflow", "launches", "notification", "retention", "tagging", "replication",
            "offloading", "cq", "granite", "fd", "communities", "sites", "security");

    /** Flat out-of-the-box model ids that launchers reference without a namespace. */
    private static final Set<String> PLATFORM_MODEL_NAMES = Set.of(
            "request_for_activation", "request_for_deactivation");

    private final WorkflowContentParser parser = new WorkflowContentParser();

    @Override
    public String getRuleId() {
        return RuleId.AEM_WF_001.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        WorkflowContentParser.WorkflowContent content =
                parser.scan(context.fileInventory().contentXml());
        if (content.launchers().isEmpty() && content.models().isEmpty()) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        violations.addAll(launcherFindings(context, content));
        violations.addAll(processFindings(context, content));
        return violations;
    }

    private List<Violation> launcherFindings(RuleContext context,
                                             WorkflowContentParser.WorkflowContent content) {
        Set<String> shippedModels = new LinkedHashSet<>();
        content.models().forEach(model -> shippedModels.add(model.name()));
        List<Violation> violations = new ArrayList<>();
        for (Launcher launcher : content.launchers()) {
            String modelPath = launcher.modelPath();
            if (isPlatformModel(modelPath) || shippedModels.contains(modelName(modelPath))) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_WF_001.id(),
                    RuleId.AEM_WF_001.defaultLevel(),
                    "MISSING_WORKFLOW_MODEL",
                    "Workflow launcher '" + launcher.nodePath() + "' starts model '" + modelPath
                            + "', which neither this project nor the platform ships. The launcher "
                            + "never starts a workflow and logs nothing.",
                    context.relativePath(launcher.file()),
                    TextRegion.forLine(launcher.line()),
                    modelPath,
                    "Point cq:workflowModel at a model the project ships (or at an existing "
                            + "out-of-the-box model), or remove the launcher if the model was retired.",
                    List.of()));
        }
        return violations;
    }

    private List<Violation> processFindings(RuleContext context,
                                            WorkflowContentParser.WorkflowContent content) {
        Set<String> projectClasses = projectClasses(context);
        Set<String> projectPackageRoots = packageRoots(projectClasses);
        if (projectPackageRoots.isEmpty()) {
            // No Java in this reactor: every process class comes from a bundle we cannot see.
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        for (WorkflowModel model : content.models()) {
            for (ProcessStep step : model.steps()) {
                String className = step.className();
                if (!isProjectPackage(className, projectPackageRoots)
                        || projectClasses.contains(outerClass(className))) {
                    continue;
                }
                violations.add(new Violation(
                        RuleId.AEM_WF_001.id(),
                        RuleId.AEM_WF_001.defaultLevel(),
                        "MISSING_PROCESS_CLASS",
                        "PROCESS step in workflow model '" + model.name() + "' names class '"
                                + className + "', which no class in this reactor declares. The step "
                                + "fails at runtime with 'process class not found' and the model "
                                + "cannot complete.",
                        context.relativePath(step.file()),
                        TextRegion.forLine(step.line()),
                        className,
                        "Restore or rename the class, or update the step to the class that now "
                                + "implements the process.",
                        List.of()));
            }
        }
        return violations;
    }

    private boolean isPlatformModel(String modelPath) {
        String rest = null;
        for (String root : MODEL_ROOTS) {
            if (modelPath.startsWith(root)) {
                rest = modelPath.substring(root.length());
                break;
            }
        }
        if (rest == null) {
            // Not under a workflow model root: an expression or a path this rule cannot judge.
            return true;
        }
        if (rest.isBlank()) {
            return true;
        }
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return PLATFORM_MODEL_NAMES.contains(rest);
        }
        return PLATFORM_MODEL_NAMESPACES.contains(rest.substring(0, slash));
    }

    private String modelName(String modelPath) {
        String path = modelPath.endsWith("/")
                ? modelPath.substring(0, modelPath.length() - 1)
                : modelPath;
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Every type the reactor declares, from all four parser buckets. */
    private Set<String> projectClasses(RuleContext context) {
        Set<String> classes = new LinkedHashSet<>();
        context.components().forEach(component -> classes.add(component.getFqn()));
        context.slingModels().forEach(model -> classes.add(model.getFqn()));
        context.javaClasses().forEach(node -> classes.add(node.getFqn()));
        classes.addAll(context.index().getOcdLookup().keySet());
        return classes;
    }

    /** Two-segment package roots ("com.acme"), the granularity a project owns. */
    private Set<String> packageRoots(Set<String> classes) {
        Set<String> roots = new LinkedHashSet<>();
        for (String fqn : classes) {
            if (fqn == null || !fqn.contains(".")) {
                continue;
            }
            String[] segments = outerClass(fqn).split("\\.");
            if (segments.length < 2) {
                continue;
            }
            int take = Math.min(2, segments.length - 1);
            StringBuilder root = new StringBuilder(segments[0]);
            for (int i = 1; i < take; i++) {
                root.append('.').append(segments[i]);
            }
            roots.add(root.toString());
        }
        return roots;
    }

    private boolean isProjectPackage(String className, Set<String> projectPackageRoots) {
        String outer = outerClass(className);
        if (!outer.contains(".")) {
            return false;
        }
        for (String root : projectPackageRoots) {
            if (outer.startsWith(root + ".")) {
                return true;
            }
        }
        return false;
    }

    /** {@code com.acme.Step$Inner} -> {@code com.acme.Step}. */
    private String outerClass(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }
}
