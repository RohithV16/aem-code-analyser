package io.github.rohithv16.aem.analyser.core.parser.java.visitor;

import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.SlingModelNode;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FEAT-PARSE-03: extracts Sling Models ({@code @Model}), including adaptables,
 * adapter interfaces, default injection strategy and {@code @OSGiService} injections.
 */
public final class SlingModelVisitor {

    private static final Pattern TYPE_TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]*");
    private static final Set<String> JDK_CONTAINER_TYPES = Set.of(
            "List", "Set", "Map", "Collection", "Iterable", "Optional", "String", "Integer", "Long",
            "Boolean", "Double", "Float", "Object", "CharSequence", "Number", "Class", "Void");

    public Optional<SlingModelNode> extract(TypeDeclaration<?> type,
                                            String packageName,
                                            Map<String, String> imports,
                                            Path filePath) {
        return extract(type, packageName, imports, Map.of(), filePath);
    }

    public Optional<SlingModelNode> extract(TypeDeclaration<?> type,
                                            String packageName,
                                            Map<String, String> imports,
                                            Map<String, String> localTypes,
                                            Path filePath) {
        Optional<AnnotationExpr> modelAnnotation = AnnotationUtils.findAnnotation(
                type.getAnnotations(), "Model");
        if (modelAnnotation.isEmpty() || !type.isClassOrInterfaceDeclaration()) {
            return Optional.empty();
        }
        AnnotationExpr annotation = modelAnnotation.get();

        Set<String> adaptables = new LinkedHashSet<>();
        AnnotationUtils.memberValues(annotation, "adaptables").stream()
                .map(s -> AnnotationUtils.resolveTypeName(s, packageName, imports, localTypes))
                .forEach(adaptables::add);

        Set<String> adapters = new LinkedHashSet<>();
        AnnotationUtils.memberValues(annotation, "adapters").stream()
                .map(s -> AnnotationUtils.resolveTypeName(s, packageName, imports, localTypes))
                .forEach(adapters::add);
        if (adapters.isEmpty()) {
            // when no adapters are declared, the model class itself is the adapter
            type.asClassOrInterfaceDeclaration().getImplementedTypes().stream()
                    .map(t -> AnnotationUtils.resolveTypeName(t.getNameWithScope(), packageName, imports, localTypes))
                    .forEach(adapters::add);
        }

        String strategyValue = AnnotationUtils.memberValue(annotation, "defaultInjectionStrategy")
                .orElse("REQUIRED");
        SlingModelNode.DefaultInjectionStrategy strategy =
                strategyValue.endsWith("OPTIONAL")
                        ? SlingModelNode.DefaultInjectionStrategy.OPTIONAL
                        : SlingModelNode.DefaultInjectionStrategy.REQUIRED;

        String resourceType = AnnotationUtils.findAnnotation(type.getAnnotations(), "ResourceType")
                .flatMap(a -> AnnotationUtils.memberValue(a, "value"))
                .or(() -> AnnotationUtils.memberValue(annotation, "resourceType"))
                .orElse(null);

        Set<String> injectedServices = new LinkedHashSet<>();
        List<SlingModelNode.InjectedField> injectedFields = new ArrayList<>();
        for (FieldDeclaration field : type.getFields()) {
            Set<String> fieldAnnotations = field.getAnnotations().stream()
                    .map(a -> a.getName().getIdentifier())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            boolean optional = !fieldAnnotations.contains("Required")
                    && (fieldAnnotations.contains("Optional")
                    || fieldAnnotations.contains("Default")
                    || annotationOptional(field, "SlingObject")
                    || annotationOptional(field, "RequestAttribute")
                    || injectionStrategyOptional(field)
                    || strategy == SlingModelNode.DefaultInjectionStrategy.OPTIONAL);
            String viaValue = AnnotationUtils.findAnnotation(field.getAnnotations(), "Via")
                    .flatMap(via -> AnnotationUtils.memberValue(via, "value"))
                    .orElse(null);
            String viaType = AnnotationUtils.findAnnotation(field.getAnnotations(), "Via")
                    .flatMap(via -> AnnotationUtils.memberValue(via, "type"))
                    .map(SlingModelVisitor::simpleName)
                    .orElse(null);
            for (var variable : field.getVariables()) {
                String fieldType = AnnotationUtils.resolveTypeName(
                        AnnotationUtils.elementType(variable.getType().asString()),
                        packageName, imports, localTypes);
                var location = MetatypeOcdVisitor.sourceLocation(variable, filePath);
                injectedFields.add(new SlingModelNode.InjectedField(
                        variable.getNameAsString(), fieldType, optional, fieldAnnotations,
                        viaValue, viaType, location.line(), location.column()));
            }
            if (AnnotationUtils.hasAnnotation(field.getAnnotations(), "OSGiService")) {
                field.getVariables().forEach(variable ->
                        injectedServices.add(AnnotationUtils.resolveTypeName(
                                AnnotationUtils.elementType(variable.getType().asString()), packageName, imports)));
            }
        }

        Set<String> referencedTypes = new LinkedHashSet<>();
        AnnotationUtils.findAnnotation(type.getAnnotations(), "Via")
                .flatMap(viaAnnotation -> AnnotationUtils.memberValue(viaAnnotation, "value"))
                .ifPresent(viaType -> addReferencedType(viaType, packageName, imports, localTypes, referencedTypes));
        boolean exported = AnnotationUtils.findAnnotation(type.getAnnotations(), "Exporter").isPresent();
        if (type.isClassOrInterfaceDeclaration()) {
            type.asClassOrInterfaceDeclaration().getExtendedTypes().forEach(t ->
                    addReferencedType(t.getNameWithScope(), packageName, imports, localTypes, referencedTypes));
            type.asClassOrInterfaceDeclaration().getImplementedTypes().forEach(t ->
                    addReferencedType(t.getNameWithScope(), packageName, imports, localTypes, referencedTypes));
        }
        for (FieldDeclaration field : type.getFields()) {
            field.getVariables().forEach(variable ->
                    addReferencedType(variable.getType().asString(), packageName, imports, localTypes, referencedTypes));
        }
        for (var constructor : type.getConstructors()) {
            constructor.getParameters().forEach(parameter ->
                    addReferencedType(parameter.getType().asString(), packageName, imports, localTypes, referencedTypes));
        }
        for (var method : type.getMethods()) {
            addReferencedType(method.getType().asString(), packageName, imports, localTypes, referencedTypes);
            method.getParameters().forEach(parameter ->
                    addReferencedType(parameter.getType().asString(), packageName, imports, localTypes, referencedTypes));
        }

        EnumSet<JavaClassNode.ModifierKind> modifiers = EnumSet.noneOf(JavaClassNode.ModifierKind.class);
        if (type.isPublic()) {
            modifiers.add(JavaClassNode.ModifierKind.PUBLIC);
        }

        String fqn = type.getFullyQualifiedName().orElse(type.getNameAsString());
        return Optional.of(new SlingModelNode(fqn, modifiers,
                type.asClassOrInterfaceDeclaration().getExtendedTypes().isNonEmpty()
                        ? AnnotationUtils.resolveTypeName(
                        type.asClassOrInterfaceDeclaration().getExtendedTypes().get(0).getNameWithScope(),
                        packageName, imports)
                        : null,
                Set.of(),
                filePath,
                MetatypeOcdVisitor.sourceLocation(type, filePath),
                adaptables, adapters, resourceType, strategy, injectedServices, type.isNestedType(),
                referencedTypes, exported, injectedFields));
    }

    /**
     * True when the annotation itself marks the injection optional: {@code optional = true} on
     * {@code @SlingObject}/{@code @RequestAttribute}, or {@code injectionStrategy = OPTIONAL}.
     */
    private static boolean annotationOptional(FieldDeclaration field, String annotationName) {
        return AnnotationUtils.findAnnotation(field.getAnnotations(), annotationName)
                .map(annotation -> "true".equalsIgnoreCase(
                                AnnotationUtils.memberValue(annotation, "optional").orElse(""))
                        || AnnotationUtils.memberValue(annotation, "injectionStrategy")
                        .map(value -> value.endsWith("OPTIONAL")).orElse(false))
                .orElse(false);
    }

    /** {@code @ValueMapValue(injectionStrategy = OPTIONAL)} and friends. */
    private static boolean injectionStrategyOptional(FieldDeclaration field) {
        return field.getAnnotations().stream()
                .anyMatch(annotation -> AnnotationUtils.memberValue(annotation, "injectionStrategy")
                        .map(value -> value.endsWith("OPTIONAL")).orElse(false));
    }

    private static String simpleName(String typeName) {
        if (typeName == null) {
            return null;
        }
        int dot = typeName.lastIndexOf('.');
        return dot >= 0 ? typeName.substring(dot + 1) : typeName;
    }

    private void addReferencedType(String rawType, String packageName, Map<String, String> imports,
                                   Map<String, String> localTypes, Set<String> referencedTypes) {
        if (rawType == null || rawType.isBlank()) {
            return;
        }
        Matcher matcher = TYPE_TOKEN.matcher(rawType);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() <= 1 || JDK_CONTAINER_TYPES.contains(token)) {
                continue;
            }
            referencedTypes.add(AnnotationUtils.resolveTypeName(token, packageName, imports, localTypes));
            referencedTypes.add(token);
        }
    }
}
