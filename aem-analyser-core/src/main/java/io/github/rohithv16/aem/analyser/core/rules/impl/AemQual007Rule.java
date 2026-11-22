package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.SlingModelNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.SlingModelNode.InjectedField;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rule AEM-QUAL-007 — a Sling Model whose declared adaptables cannot satisfy its own injections.
 *
 * <p>Sling Models resolves an injection against the <em>adaptable</em>, not against the request the
 * adaptable came from. A model declared {@code adaptables = Resource.class} therefore cannot inject
 * a {@code SlingHttpServletRequest}, a response or a {@code @RequestAttribute}: the build, the
 * bundle and the model registration all succeed, and the failure appears only when
 * {@code request.adaptTo(Model.class)} throws a {@code ModelException} (required injection) or
 * renders null fields (optional injection). No compiler or Cloud Manager check reads adaptables
 * against the fields.</p>
 *
 * <p>Reported shapes:</p>
 * <ul>
 *   <li>a required field whose type is request/response-only, or a {@code @RequestAttribute},
 *       while the model declares neither a Sling/HTTP request adaptable;</li>
 *   <li>a {@code @Via} injection of another project model whose adaptables cannot accept the
 *       adaptable the via provider produces (for example {@code @Via(type = ChildResource.class)}
 *       gives a {@code Resource}, so the target model must be adaptable from a Resource).</li>
 * </ul>
 *
 * <p>Optional and {@code @Default} fields are silent: a failed optional injection leaves null but
 * the model still registers, so it is not the registrability defect this rule covers. A model that
 * declares {@code Object} among its adaptables, or one with no adaptables at all, cannot be judged
 * and is skipped rather than guessed.</p>
 */
public final class AemQual007Rule implements RuleEvaluator {

    private static final Set<String> REQUEST_TYPES = Set.of(
            "org.apache.sling.api.SlingHttpServletRequest",
            "org.apache.sling.api.SlingJakartaHttpServletRequest",
            "javax.servlet.http.HttpServletRequest",
            "jakarta.servlet.http.HttpServletRequest",
            "org.apache.sling.api.SlingHttpServletResponse",
            "org.apache.sling.api.SlingJakartaHttpServletResponse",
            "javax.servlet.http.HttpServletResponse",
            "jakarta.servlet.http.HttpServletResponse",
            "javax.servlet.RequestDispatcher",
            "jakarta.servlet.RequestDispatcher");

    /** Fields with none of these are not injection points (assigned in the constructor, constants). */
    private static final Set<String> INJECTION_ANNOTATIONS = Set.of(
            "Inject", "Autowired", "SlingObject", "RequestAttribute", "Self", "ScriptVariable",
            "ValueMapValue", "ChildResource", "ResourcePath", "OSGiService");

    private static final String RESOURCE = "org.apache.sling.api.resource.Resource";
    private static final String OBJECT = "java.lang.Object";
    private static final String BEAN_PROPERTY = "BeanProperty";

    @Override
    public String getRuleId() {
        return RuleId.AEM_QUAL_007.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        Map<String, SlingModelNode> models = new LinkedHashMap<>();
        for (SlingModelNode model : context.slingModels()) {
            models.put(model.getFqn(), model);
        }
        List<Violation> violations = new ArrayList<>();
        for (SlingModelNode model : models.values()) {
            List<String> adaptables = new ArrayList<>(model.getAdaptables());
            if (adaptables.isEmpty() || adaptables.contains(OBJECT)) {
                // An adapter-only model or an Object-adaptable model: nothing static to compare.
                continue;
            }
            boolean requestAvailable = adaptables.stream().anyMatch(AemQual007Rule::isRequestAdaptable);
            for (InjectedField field : model.getInjectedFields()) {
                if (field.optional() || !isInjectionPoint(field)) {
                    continue;
                }
                boolean needsRequest = field.hasAnnotation("RequestAttribute")
                        || REQUEST_TYPES.contains(field.typeName());
                if (needsRequest && !requestAvailable) {
                    violations.add(violation(context, model, field, "REQUEST_INJECTION_WITHOUT_REQUEST_ADAPTABLE",
                            "Model '" + model.getFqn() + "' is adaptable only from " + adaptables
                                    + " but requires the request for field '" + field.name()
                                    + "'; the injection fails and the model cannot be adapted.",
                            "Add SlingHttpServletRequest (or HttpServletRequest) to the model's"
                                    + " adaptables, or inject this value from the resource."));
                    continue;
                }
                Violation via = viaMismatch(context, model, field, adaptables, models);
                if (via != null) {
                    violations.add(via);
                }
            }
        }
        return violations;
    }

    /**
     * {@code @Via} changes the adaptable before injection. Only the providers whose result is known
     * statically are judged; a bean property other than {@code resource}/{@code request} could be
     * anything, so the rule says nothing about it.
     */
    private Violation viaMismatch(RuleContext context, SlingModelNode model, InjectedField field,
                                  List<String> adaptables, Map<String, SlingModelNode> models) {
        if (field.viaValue() == null && field.viaType() == null) {
            return null;
        }
        SlingModelNode target = models.get(field.typeName());
        if (target == null || target.getFqn().equals(model.getFqn())) {
            return null;
        }
        List<String> targetAdaptables = new ArrayList<>(target.getAdaptables());
        if (targetAdaptables.isEmpty() || targetAdaptables.contains(OBJECT)) {
            return null;
        }
        String viaAdaptable = viaAdaptable(field, adaptables);
        if (viaAdaptable == null) {
            return null;
        }
        boolean accepted = "original".equals(viaAdaptable)
                ? adaptables.stream().anyMatch(adaptable -> accepts(targetAdaptables, adaptable))
                : accepts(targetAdaptables, viaAdaptable);
        if (accepted) {
            return null;
        }
        return violation(context, model, field, "VIA_ADAPTABLE_MISMATCH",
                "@Via injects '" + field.name() + "' from a "
                        + ("original".equals(viaAdaptable) ? "unchanged adaptable" : viaAdaptable)
                        + ", but target model '" + target.getFqn() + "' is adaptable only from "
                        + targetAdaptables + "; the injection fails.",
                "Align the target model's adaptables with the @Via provider, or use a via provider"
                        + " that produces an adaptable the model declares.");
    }

    /**
     * @return the adaptable the via provider produces ({@code resource}, a request FQN,
     *         {@code original}) or {@code null} when it cannot be known statically
     */
    private static String viaAdaptable(InjectedField field, List<String> adaptables) {
        String viaType = field.viaType() == null ? BEAN_PROPERTY : field.viaType();
        String value = field.viaValue() == null ? "" : field.viaValue();
        return switch (viaType) {
            case "ChildResource", "ForcedResourceType", "ResourceSuperType" -> RESOURCE;
            case BEAN_PROPERTY -> {
                if (value.isEmpty()) {
                    yield "original";
                }
                if ("resource".equals(value)) {
                    yield RESOURCE;
                }
                if ("request".equals(value)) {
                    yield adaptables.stream().filter(AemQual007Rule::isRequestAdaptable)
                            .findFirst().orElse(null);
                }
                yield null;
            }
            default -> null;
        };
    }

    /**
     * Sling Models adapts the adaptable to a declared adaptable type, so a request satisfies a
     * {@code Resource} declaration too — a Resource does not satisfy a request declaration.
     */
    private static boolean accepts(List<String> targetAdaptables, String adaptable) {
        for (String declared : targetAdaptables) {
            if (declared.equals(adaptable)) {
                return true;
            }
            if (isRequestAdaptable(adaptable) && RESOURCE.equals(declared)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRequestAdaptable(String typeName) {
        return typeName != null && (typeName.equals("org.apache.sling.api.SlingHttpServletRequest")
                || typeName.equals("org.apache.sling.api.SlingJakartaHttpServletRequest")
                || typeName.equals("javax.servlet.http.HttpServletRequest")
                || typeName.equals("jakarta.servlet.http.HttpServletRequest"));
    }

    private static boolean isInjectionPoint(InjectedField field) {
        return field.annotations().stream().anyMatch(INJECTION_ANNOTATIONS::contains);
    }

    private Violation violation(RuleContext context, SlingModelNode model, InjectedField field,
                                String subtype, String message, String remediation) {
        Path file = model.getFilePath();
        int line = Math.max(1, field.line());
        int column = Math.max(1, field.column());
        return new Violation(
                RuleId.AEM_QUAL_007.id(),
                RuleId.AEM_QUAL_007.defaultLevel(),
                subtype,
                message,
                context.relativePath(file),
                new TextRegion(line, column, line, column + Math.max(1, field.name().length())),
                field.name(),
                remediation,
                List.of());
    }
}
