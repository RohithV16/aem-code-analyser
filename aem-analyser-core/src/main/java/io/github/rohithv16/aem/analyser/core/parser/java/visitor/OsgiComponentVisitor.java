package io.github.rohithv16.aem.analyser.core.parser.java.visitor;

import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FEAT-PARSE-01: extracts Declarative Services {@code @Component} declarations,
 * exposed service interfaces, {@code @Reference} injections, {@code @Designate}
 * OCD designations, configuration PID/policy/immediate semantics and component properties.
 */
public final class OsgiComponentVisitor {

    public Optional<OSGiComponentNode> extract(TypeDeclaration<?> type,
                                               String packageName,
                                               Map<String, String> imports,
                                               Path filePath) {
        return extract(type, packageName, imports, Map.of(), filePath);
    }

    public Optional<OSGiComponentNode> extract(TypeDeclaration<?> type,
                                               String packageName,
                                               Map<String, String> imports,
                                               Map<String, String> localTypes,
                                               Path filePath) {
        Optional<AnnotationExpr> componentAnnotation = AnnotationUtils.findAnnotation(
                type.getAnnotations(), "Component");
        if (componentAnnotation.isEmpty()) {
            return Optional.empty();
        }
        AnnotationExpr annotation = componentAnnotation.get();

        Optional<String> name = AnnotationUtils.memberValue(annotation, "name")
                .filter(s -> !s.isBlank());
        List<String> configurationPids = AnnotationUtils.memberValues(annotation, "configurationPid").stream()
                .filter(s -> !s.isBlank())
                .toList();
        String configurationPolicyValue = AnnotationUtils.memberValue(annotation, "configurationPolicy")
                .orElse(null);
        OSGiComponentNode.ConfigurationPolicy configurationPolicy =
                OSGiComponentNode.ConfigurationPolicy.OPTIONAL;
        if (configurationPolicyValue != null) {
            if (configurationPolicyValue.endsWith("REQUIRE")) {
                configurationPolicy = OSGiComponentNode.ConfigurationPolicy.REQUIRE;
            } else if (configurationPolicyValue.endsWith("IGNORE")) {
                configurationPolicy = OSGiComponentNode.ConfigurationPolicy.IGNORE;
            }
        }
        Optional<String> immediateValue = AnnotationUtils.memberValue(annotation, "immediate");
        boolean immediateExplicitlySet = immediateValue.isPresent();
        boolean immediate = immediateValue.map(Boolean::parseBoolean).orElse(false);

        Set<String> implementedInterfaces = new LinkedHashSet<>();
        if (type.isClassOrInterfaceDeclaration()) {
            type.asClassOrInterfaceDeclaration().getImplementedTypes().stream()
                    .map(t -> AnnotationUtils.resolveTypeName(t.getNameWithScope(), packageName, imports, localTypes))
                    .forEach(implementedInterfaces::add);
        }

        Set<String> serviceInterfaces = new LinkedHashSet<>();
        String componentClassName = type.getFullyQualifiedName().orElse(type.getNameAsString());
        boolean serviceAttributeDeclared = annotation.isNormalAnnotationExpr()
                && annotation.asNormalAnnotationExpr().getPairs().stream()
                        .anyMatch(pair -> pair.getNameAsString().equals("service"));
        if (serviceAttributeDeclared) {
            // DS semantics: an explicit service member overrides the implemented interfaces.
            // @Component(service = {}) therefore registers no service at all.
            AnnotationUtils.memberValues(annotation, "service").stream()
                    .filter(s -> !s.isBlank())
                    .map(s -> AnnotationUtils.resolveTypeName(s, packageName, imports, localTypes))
                    .forEach(serviceInterfaces::add);
        } else {
            serviceInterfaces.addAll(implementedInterfaces);
        }

        String designatedOcdFqn = AnnotationUtils.findAnnotation(type.getAnnotations(), "Designate")
                .flatMap(a -> AnnotationUtils.memberValue(a, "ocd"))
                .map(s -> AnnotationUtils.resolveTypeName(s, packageName, imports, localTypes))
                .orElse(null);

        Set<String> componentProperties = new LinkedHashSet<>();
        AnnotationUtils.memberValues(annotation, "property").forEach(componentProperties::add);
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("property")
                        && pair.getValue().isArrayInitializerExpr()) {
                    pair.getValue().asArrayInitializerExpr().getValues().stream()
                            .map(AnnotationUtils::expressionToString)
                            .forEach(componentProperties::add);
                }
            }
        }

        List<OSGiComponentNode.ServiceReference> referenceDetails =
                extractReferenceDetails(type, packageName, imports, localTypes, filePath);
        Map<String, String> references = new LinkedHashMap<>();
        referenceDetails.forEach(reference -> references.put(reference.name(), reference.targetType()));

        String factoryId = AnnotationUtils.memberValue(annotation, "factory")
                .filter(value -> !value.isBlank())
                .orElse(null);
        boolean enabled = AnnotationUtils.memberValue(annotation, "enabled")
                .map(Boolean::parseBoolean)
                .orElse(true);
        boolean programmaticActivation = detectProgrammaticActivation(type, imports);

        List<OSGiComponentNode.DeclaredField> declaredFields = new ArrayList<>();
        for (FieldDeclaration field : type.getFields()) {
            boolean isStatic = field.isStatic();
            field.getVariables().forEach(variable -> {
                String fieldType = AnnotationUtils.resolveTypeName(
                        AnnotationUtils.elementType(variable.getType().asString()), packageName, imports);
                SourceLocation loc = MetatypeOcdVisitor.sourceLocation(variable, filePath);
                declaredFields.add(new OSGiComponentNode.DeclaredField(
                        variable.getNameAsString(), fieldType, loc, isStatic));
            });
        }

        return Optional.of(new OSGiComponentNode(
                componentClassName,
                name.orElse(null),
                JavaClassNode.Kind.CLASS,
                modifiersOf(type),
                superclassOf(type, packageName, imports),
                implementedInterfaces,
                filePath,
                MetatypeOcdVisitor.sourceLocation(type, filePath),
                configurationPids,
                configurationPolicy,
                immediate,
                immediateExplicitlySet,
                serviceInterfaces,
                references,
                designatedOcdFqn,
                componentProperties,
                declaredFields,
                factoryId,
                enabled,
                programmaticActivation,
                referenceDetails));
    }

    /**
     * A component that touches the raw OSGi registry (ServiceTracker/BundleContext) may be created and
     * activated by another component even without a service interface of its own.
     */
    private boolean detectProgrammaticActivation(TypeDeclaration<?> type, Map<String, String> imports) {
        boolean imported = imports.values().stream()
                .anyMatch(value -> value.contains("ServiceTracker") || value.contains("BundleContext"));
        if (imported) {
            return true;
        }
        return type.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                .map(variable -> variable.getType().asString())
                .anyMatch(name -> name.contains("ServiceTracker") || name.contains("BundleContext"));
    }

    /**
     * Extracts {@code @Reference} field/method/parameter targets. Public because references declared
     * in a plain (non-component) base class must still reach the graph: they are attributed to the
     * inheriting component at graph-build time.
     */
    public Map<String, String> extractReferences(TypeDeclaration<?> type, String packageName,
                                                 Map<String, String> imports, Map<String, String> localTypes) {
        Map<String, String> references = new LinkedHashMap<>();
        extractReferenceDetails(type, packageName, imports, localTypes, null)
                .forEach(reference -> references.put(reference.name(), reference.targetType()));
        return references;
    }

    /**
     * Every {@code @Reference} with its cardinality and source location, for rules that must tell a
     * mandatory injection (a missing provider blocks activation) from an optional one.
     *
     * @param filePath the declaring source file, used for the reference location; may be null when
     *                 the caller only needs the name-to-type map
     */
    public List<OSGiComponentNode.ServiceReference> extractReferenceDetails(
            TypeDeclaration<?> type, String packageName, Map<String, String> imports,
            Map<String, String> localTypes, Path filePath) {
        List<OSGiComponentNode.ServiceReference> references = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<BodyDeclaration<?>> members = new ArrayList<>();
        members.addAll(type.getFields());
        members.addAll(type.getMethods());
        for (BodyDeclaration<?> member : members) {
            Optional<AnnotationExpr> reference = AnnotationUtils.findAnnotation(
                    member.getAnnotations(), "Reference");
            if (reference.isEmpty()) {
                continue;
            }
            String targetService = AnnotationUtils.memberValue(reference.get(), "service")
                    .map(s -> AnnotationUtils.resolveTypeName(s, packageName, imports, localTypes))
                    .orElse(null);
            OSGiComponentNode.ReferenceCardinality cardinality = cardinalityOf(reference.get());

            if (member instanceof FieldDeclaration field) {
                field.getVariables().forEach(variable -> {
                    String fieldType = AnnotationUtils.resolveTypeName(
                            AnnotationUtils.elementType(variable.getType().asString()), packageName, imports);
                    String target = targetService != null ? targetService : fieldType;
                    if (seen.add(variable.getNameAsString())) {
                        references.add(new OSGiComponentNode.ServiceReference(
                                variable.getNameAsString(), target, cardinality,
                                MetatypeOcdVisitor.sourceLocation(variable, filePath)));
                    }
                });
            } else if (member instanceof com.github.javaparser.ast.body.MethodDeclaration method
                    && method.getParameters().isNonEmpty()) {
                // DS 1.4 bind method: @Reference void bind(Service service)
                var parameter = method.getParameters().get(0);
                String target = targetService != null ? targetService
                        : AnnotationUtils.resolveTypeName(AnnotationUtils.elementType(parameter.getType().asString()),
                                packageName, imports);
                if (seen.add(method.getNameAsString())) {
                    references.add(new OSGiComponentNode.ServiceReference(
                            method.getNameAsString(), target, cardinality,
                            MetatypeOcdVisitor.sourceLocation(method, filePath)));
                }
            }
        }

        // DS 1.4 constructor injection: @Activate Ctor(@Reference Service service)
        for (var constructor : type.getConstructors()) {
            for (var parameter : constructor.getParameters()) {
                Optional<AnnotationExpr> reference = AnnotationUtils.findAnnotation(
                        parameter.getAnnotations(), "Reference");
                if (reference.isEmpty()) {
                    continue;
                }
                String target = AnnotationUtils.memberValue(reference.get(), "service")
                        .map(s -> AnnotationUtils.resolveTypeName(s, packageName, imports, localTypes))
                        .orElseGet(() -> AnnotationUtils.resolveTypeName(
                                AnnotationUtils.elementType(parameter.getType().asString()), packageName, imports));
                if (seen.add(parameter.getNameAsString())) {
                    references.add(new OSGiComponentNode.ServiceReference(
                            parameter.getNameAsString(), target, cardinalityOf(reference.get()),
                            MetatypeOcdVisitor.sourceLocation(parameter, filePath)));
                }
            }
        }
        return references;
    }

    /**
     * DS cardinality from the annotation literal. Anything other than OPTIONAL/MULTIPLE/AT_LEAST_ONE
     * is MANDATORY, which is the DS default when the member is absent.
     */
    private static OSGiComponentNode.ReferenceCardinality cardinalityOf(AnnotationExpr reference) {
        String value = AnnotationUtils.memberValue(reference, "cardinality").orElse("");
        if (value.contains("OPTIONAL")) {
            return OSGiComponentNode.ReferenceCardinality.OPTIONAL;
        }
        if (value.contains("MULTIPLE")) {
            return OSGiComponentNode.ReferenceCardinality.MULTIPLE;
        }
        if (value.contains("AT_LEAST_ONE")) {
            return OSGiComponentNode.ReferenceCardinality.AT_LEAST_ONE;
        }
        return OSGiComponentNode.ReferenceCardinality.MANDATORY;
    }

    private String superclassOf(TypeDeclaration<?> type, String packageName, Map<String, String> imports) {
        if (type.isClassOrInterfaceDeclaration()
                && type.asClassOrInterfaceDeclaration().getExtendedTypes().isNonEmpty()) {
            return AnnotationUtils.resolveTypeName(
                    type.asClassOrInterfaceDeclaration().getExtendedTypes().get(0).getNameWithScope(),
                    packageName, imports);
        }
        return null;
    }

    static EnumSet<JavaClassNode.ModifierKind> modifiersOf(TypeDeclaration<?> type) {
        EnumSet<JavaClassNode.ModifierKind> modifiers = EnumSet.noneOf(JavaClassNode.ModifierKind.class);
        if (type.isPublic()) {
            modifiers.add(JavaClassNode.ModifierKind.PUBLIC);
        }
        if (type.getModifiers().stream().anyMatch(m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.ABSTRACT)) {
            modifiers.add(JavaClassNode.ModifierKind.ABSTRACT);
        }
        return modifiers;
    }
}
