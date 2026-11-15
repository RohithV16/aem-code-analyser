package io.github.aemtools.analyser.core.parser.java.visitor;

import io.github.aemtools.analyser.core.model.graph.node.JavaClassNode;
import io.github.aemtools.analyser.core.model.graph.node.SlingModelNode;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FEAT-PARSE-03: extracts Sling Models ({@code @Model}), including adaptables,
 * adapter interfaces, default injection strategy and {@code @OSGiService} injections.
 */
public final class SlingModelVisitor {

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
                    .map(t -> AnnotationUtils.resolveTypeName(t.getNameAsString(), packageName, imports, localTypes))
                    .forEach(adapters::add);
        }

        String strategyValue = AnnotationUtils.memberValue(annotation, "defaultInjectionStrategy")
                .orElse(null);
        SlingModelNode.DefaultInjectionStrategy strategy =
                strategyValue != null && strategyValue.endsWith("REQUIRED")
                        ? SlingModelNode.DefaultInjectionStrategy.REQUIRED
                        : SlingModelNode.DefaultInjectionStrategy.OPTIONAL;

        String resourceType = AnnotationUtils.findAnnotation(type.getAnnotations(), "ResourceType")
                .flatMap(a -> AnnotationUtils.memberValue(a, "value"))
                .orElse(null);

        Set<String> injectedServices = new LinkedHashSet<>();
        for (FieldDeclaration field : type.getFields()) {
            if (AnnotationUtils.hasAnnotation(field.getAnnotations(), "OSGiService")) {
                field.getVariables().forEach(variable ->
                        injectedServices.add(AnnotationUtils.resolveTypeName(
                                AnnotationUtils.elementType(variable.getType().asString()), packageName, imports)));
            }
        }

        EnumSet<JavaClassNode.ModifierKind> modifiers = EnumSet.noneOf(JavaClassNode.ModifierKind.class);
        if (type.isPublic()) {
            modifiers.add(JavaClassNode.ModifierKind.PUBLIC);
        }

        String fqn = type.getFullyQualifiedName().orElse(type.getNameAsString());
        return Optional.of(new SlingModelNode(fqn, modifiers,
                type.asClassOrInterfaceDeclaration().getExtendedTypes().isNonEmpty()
                        ? AnnotationUtils.resolveTypeName(
                        type.asClassOrInterfaceDeclaration().getExtendedTypes().get(0).getNameAsString(),
                        packageName, imports)
                        : null,
                Set.of(),
                filePath,
                MetatypeOcdVisitor.sourceLocation(type, filePath),
                adaptables, adapters, resourceType, strategy, injectedServices));
    }
}
