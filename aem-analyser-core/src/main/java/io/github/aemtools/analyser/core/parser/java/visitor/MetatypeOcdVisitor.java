package io.github.aemtools.analyser.core.parser.java.visitor;

import io.github.aemtools.analyser.core.graph.MetatypeNameNormalizer;
import io.github.aemtools.analyser.core.model.graph.SourceLocation;
import io.github.aemtools.analyser.core.model.graph.node.JavaClassNode;
import io.github.aemtools.analyser.core.model.graph.node.OSGiOcdNode;
import io.github.aemtools.analyser.core.model.graph.node.OcdPropertyMethodNode;
import com.github.javaparser.Position;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * FEAT-PARSE-02: extracts {@code @ObjectClassDefinition} interfaces and their
 * {@code @AttributeDefinition} property methods, including explicit {@code id} overrides,
 * default values and return types, with exact source coordinates.
 */
public final class MetatypeOcdVisitor {

    public Optional<OSGiOcdNode> extract(TypeDeclaration<?> type, Path filePath) {
        Optional<AnnotationExpr> ocdAnnotation = AnnotationUtils.findAnnotation(
                type.getAnnotations(), "ObjectClassDefinition");
        if (ocdAnnotation.isEmpty()) {
            return Optional.empty();
        }

        String ocdFqn = type.getFullyQualifiedName().orElse(type.getNameAsString());
        String explicitPid = AnnotationUtils.memberValue(ocdAnnotation.get(), "pid").orElse(null);
        String metatypeName = AnnotationUtils.memberValue(ocdAnnotation.get(), "name").orElse(null);

        JavaClassNode.Kind kind = type.isAnnotationDeclaration()
                ? JavaClassNode.Kind.ANNOTATION
                : JavaClassNode.Kind.INTERFACE;
        SourceLocation location = sourceLocation(type, filePath);

        OSGiOcdNode ocdNode = new OSGiOcdNode(ocdFqn,
                explicitPid != null && !explicitPid.isBlank() ? explicitPid : ocdFqn,
                metatypeName,
                modifiersOf(type),
                filePath, location);

        for (var member : type.getMembers()) {
            if (member instanceof com.github.javaparser.ast.body.AnnotationMemberDeclaration memberDeclaration) {
                extractAttributeDefinition(memberDeclaration.getAnnotations(), memberDeclaration.getNameAsString(),
                        memberDeclaration.getType().asString(),
                        memberDeclaration.getDefaultValue(),
                        0,
                        ocdFqn, kind, filePath)
                        .ifPresent(ocdNode::addPropertyMethod);
            } else if (member instanceof com.github.javaparser.ast.body.MethodDeclaration method) {
                extractAttributeDefinition(method.getAnnotations(), method.getNameAsString(),
                        method.getType().asString(), Optional.empty(),
                        method.getParameters().size(),
                        ocdFqn, kind, filePath)
                        .ifPresent(ocdNode::addPropertyMethod);
            }
        }
        return Optional.of(ocdNode);
    }

    private Optional<OcdPropertyMethodNode> extractAttributeDefinition(
            com.github.javaparser.ast.NodeList<AnnotationExpr> annotations,
            String memberName,
            String memberType,
            Optional<com.github.javaparser.ast.expr.Expression> defaultValue,
            int parameterCount,
            String ocdFqn,
            JavaClassNode.Kind ocdKind,
            Path filePath) {
        Optional<AnnotationExpr> attributeAnnotation = AnnotationUtils.findAnnotation(
                annotations, "AttributeDefinition");

        boolean annotationOcd = ocdKind == JavaClassNode.Kind.ANNOTATION;
        if (attributeAnnotation.isEmpty() && !annotationOcd) {
            return Optional.empty();
        }
        // Parameterless members of annotation-based OCDs are attribute definitions (OSGi R7 §112.11)
        if (attributeAnnotation.isEmpty() && parameterCount > 0) {
            return Optional.empty();
        }

        String explicitId = attributeAnnotation
                .flatMap(a -> AnnotationUtils.memberValue(a, "id"))
                .orElse(null);
        List<String> defaults = new ArrayList<>();
        attributeAnnotation.ifPresent(a -> defaults.addAll(
                AnnotationUtils.memberValues(a, "defaultValue").stream()
                        .filter(s -> !s.isBlank())
                        .toList()));
        if (defaults.isEmpty()) {
            defaultValue.ifPresent(expr -> defaults.add(
                    AnnotationUtils.expressionToString(expr)));
        }
        boolean required = attributeAnnotation
                .flatMap(a -> AnnotationUtils.memberValue(a, "required"))
                .map(Boolean::parseBoolean)
                .orElse(true);

        String methodName = memberName;
        String returnType = memberType;
        String normalized = MetatypeNameNormalizer.normalizeMethodName(methodName, explicitId);
        return Optional.of(new OcdPropertyMethodNode(
                ocdFqn, methodName, explicitId, normalized,
                returnType, defaults, required,
                filePath, sourceLocationOfMember(annotations, filePath)));
    }

    private SourceLocation sourceLocationOfMember(com.github.javaparser.ast.NodeList<AnnotationExpr> annotations,
                                                  Path filePath) {
        if (annotations.isEmpty()) {
            return SourceLocation.wholeFile(filePath);
        }
        return sourceLocation(annotations.get(0), filePath);
    }

    private EnumSet<JavaClassNode.ModifierKind> modifiersOf(TypeDeclaration<?> type) {
        EnumSet<JavaClassNode.ModifierKind> modifiers = EnumSet.noneOf(JavaClassNode.ModifierKind.class);
        if (type.isPublic()) {
            modifiers.add(JavaClassNode.ModifierKind.PUBLIC);
        }
        if (type.getModifiers().stream().anyMatch(m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.ABSTRACT)) {
            modifiers.add(JavaClassNode.ModifierKind.ABSTRACT);
        }
        return modifiers;
    }

    public static SourceLocation sourceLocation(com.github.javaparser.ast.Node node, Path filePath) {
        Position begin = node.getBegin().orElse(null);
        if (begin == null) {
            return SourceLocation.wholeFile(filePath);
        }
        return new SourceLocation(filePath, begin.line, begin.column);
    }
}
