package io.github.rohithv16.aem.analyser.core.parser.java.visitor;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shared helpers for annotation extraction on JavaParser ASTs.
 */
public final class AnnotationUtils {

    private AnnotationUtils() {
    }

    /** True when the annotation has the given simple name (qualified or imported). */
    public static boolean isAnnotation(AnnotationExpr annotation, String simpleName) {
        String name = annotation.getNameAsString();
        return name.equals(simpleName) || name.endsWith("." + simpleName);
    }

    public static Optional<AnnotationExpr> findAnnotation(NodeList<AnnotationExpr> annotations, String simpleName) {
        return annotations.stream()
                .filter(a -> isAnnotation(a, simpleName))
                .findFirst();
    }

    /**
     * Resolves a member value of the annotation as a string:
     * string literals are unquoted, class literals are reduced to their type name.
     */
    public static Optional<String> memberValue(AnnotationExpr annotation, String member) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals(member))
                    .map(pair -> expressionToString(pair.getValue()))
                    .findFirst();
        }
        if (annotation instanceof SingleMemberAnnotationExpr single && member.equals("value")) {
            return Optional.of(expressionToString(single.getMemberValue()));
        }
        return Optional.empty();
    }

    /**
     * Resolves all member values of an array-typed annotation member.
     */
    public static List<String> memberValues(AnnotationExpr annotation, String member) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals(member))
                    .flatMap(pair -> expressionToList(pair.getValue()).stream())
                    .toList();
        }
        if (annotation instanceof SingleMemberAnnotationExpr single && member.equals("value")) {
            return expressionToList(single.getMemberValue());
        }
        return List.of();
    }

    public static boolean hasAnnotation(NodeList<AnnotationExpr> annotations, String simpleName) {
        return findAnnotation(annotations, simpleName).isPresent();
    }

    public static String expressionToString(Expression expression) {
        if (expression instanceof StringLiteralExpr literal) {
            return literal.getValue();
        }
        if (expression instanceof ClassExpr classExpr) {
            return classExpr.getType().asString();
        }
        if (expression instanceof ArrayInitializerExpr array) {
            List<String> values = new ArrayList<>();
            array.getValues().forEach(v -> values.add(expressionToString(v)));
            return String.join(",", values);
        }
        // "sling.servlet.paths=" + Consts.PATH  /  Consts.PID
        String folded = foldConstant(expression);
        if (folded != null) {
            return folded;
        }
        return expression.toString();
    }

    /**
     * Project-wide {@code static final String} constants, published for the duration of one file
     * parse so that annotation values referencing a constant declared in ANOTHER compilation unit
     * ({@code configurationPid = Consts.PID}) still fold. Thread-scoped because parsing is parallel
     * and each worker parses one file at a time.
     */
    private static final ThreadLocal<Map<String, String>> PROJECT_CONSTANTS = new ThreadLocal<>();

    public static void setProjectConstants(Map<String, String> constants) {
        if (constants == null || constants.isEmpty()) {
            PROJECT_CONSTANTS.remove();
        } else {
            PROJECT_CONSTANTS.set(constants);
        }
    }

    public static void clearProjectConstants() {
        PROJECT_CONSTANTS.remove();
    }

    private static String foldConstant(Expression expression) {
        if (expression instanceof StringLiteralExpr literal) {
            return literal.getValue();
        }
        if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            String left = foldConstant(binary.getLeft());
            String right = foldConstant(binary.getRight());
            return left != null && right != null ? left + right : null;
        }
        String name = null;
        String qualified = null;
        if (expression instanceof NameExpr nameExpr) {
            name = nameExpr.getNameAsString();
        } else if (expression instanceof FieldAccessExpr fieldAccess) {
            name = fieldAccess.getNameAsString();
            qualified = fieldAccess.toString();
        }
        if (name == null) {
            return null;
        }
        final String constantName = name;
        String local = expression.findCompilationUnit()
                .flatMap(cu -> cu.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                        .filter(field -> field.isStatic() && field.isFinal())
                        .flatMap(field -> field.getVariables().stream())
                        .filter(variable -> variable.getNameAsString().equals(constantName))
                        .filter(variable -> variable.getInitializer().isPresent())
                        .map(variable -> foldConstant(variable.getInitializer().get()))
                        .filter(java.util.Objects::nonNull)
                        .findFirst())
                .orElse(null);
        if (local != null) {
            return local;
        }
        Map<String, String> projectConstants = PROJECT_CONSTANTS.get();
        if (projectConstants == null) {
            return null;
        }
        // Constant declared in another compilation unit. Qualified references (Type.NAME) are
        // resolved through this file's imports first so same-named constants in different classes
        // cannot collide; the bare simple name is only a last resort.
        if (qualified != null) {
            String byType = projectConstants.get(qualified);
            if (byType != null) {
                return byType;
            }
            String importName = qualified.substring(0, qualified.lastIndexOf('.'));
            Optional<com.github.javaparser.ast.ImportDeclaration> imported = expression
                    .findCompilationUnit()
                    .flatMap(cu -> cu.getImports().stream()
                            .filter(imp -> !imp.isAsterisk() && !imp.isStatic()
                                    && imp.getNameAsString().endsWith("." + importName))
                            .findFirst());
            if (imported.isPresent()) {
                String byFqn = projectConstants.get(imported.get().getNameAsString() + "." + name);
                if (byFqn != null) {
                    return byFqn;
                }
            }
        }
        return projectConstants.get(name);
    }

    public static List<String> expressionToList(Expression expression) {
        List<String> values = new ArrayList<>();
        if (expression instanceof ArrayInitializerExpr array) {
            array.getValues().forEach(v -> values.add(expressionToString(v)));
        } else {
            values.add(expressionToString(expression));
        }
        return values;
    }

    /**
     * Builds a simple-name to FQN type resolution map from the compilation unit package and imports.
     */
    public static Map<String, String> buildTypeResolver(String packageName, NodeList<ImportDeclaration> imports) {
        Map<String, String> resolver = new LinkedHashMap<>();
        for (ImportDeclaration imp : imports) {
            String imported = imp.getNameAsString();
            if (!imp.isAsterisk()) {
                int dot = imported.lastIndexOf('.');
                if (dot > 0) {
                    resolver.put(imported.substring(dot + 1), imported);
                }
            }
        }
        return resolver;
    }

    /**
     * Strips array suffixes and generic type arguments: {@code List<Config>} -> {@code List},
     * {@code Config[]} -> {@code Config}.
     */
    public static String elementType(String typeName) {
        if (typeName == null) {
            return null;
        }
        String type = typeName.strip();
        int generic = type.indexOf('<');
        if (generic > 0) {
            type = type.substring(0, generic).strip();
        }
        while (type.endsWith("[]")) {
            type = type.substring(0, type.length() - 2).strip();
        }
        return type;
    }

    /** Types implicitly imported from {@code java.lang} (no explicit import in source). */
    private static final Set<String> JAVA_LANG_TYPES = Set.of(
            "Object", "String", "Boolean", "Integer", "Long", "Double", "Float", "Short", "Byte",
            "Character", "Runnable", "Comparable", "Iterable", "AutoCloseable", "Cloneable",
            "Thread", "Exception", "RuntimeException", "IllegalArgumentException",
            "IllegalStateException", "Enum", "Record", "Number", "CharSequence");

    /**
     * Resolves a type name written in source against the package and import map.
     */
    public static String resolveTypeName(String typeName, String packageName, Map<String, String> imports) {
        return resolveTypeName(typeName, packageName, imports, null);
    }

    /**
     * Resolves a type name with an optional compilation-unit type index
     * (simple name -> JavaParser fully-qualified name). This is required to resolve
     * references to <em>nested</em> types declared in the same file: JavaParser emits
     * {@code Outer.Inner} fully-qualified names for the declaration, but an unqualified
     * {@code Inner} reference would otherwise be wrongly resolved against the package,
     * breaking the match between {@code @Designate} targets, injected receiver types and
     * registered OCD nodes.
     */
    public static String resolveTypeName(String typeName, String packageName, Map<String, String> imports,
                                         Map<String, String> localTypes) {
        if (typeName == null) {
            return null;
        }
        if (typeName.contains(".")) {
            // fully qualified already; resolve possibly-imported prefix (inner classes)
            int dot = typeName.indexOf('.');
            String head = typeName.substring(0, dot);
            String tail = typeName.substring(dot + 1);
            if (localTypes != null && localTypes.containsKey(head)) {
                // e.g. "FactoryServiceImpl.TenantConfig" where FactoryServiceImpl is declared locally
                return localTypes.get(head) + "." + tail;
            }
            return imports.getOrDefault(typeName, typeName);
        }
        if (imports.containsKey(typeName)) {
            return imports.get(typeName);
        }
        if (localTypes != null && localTypes.containsKey(typeName)) {
            return localTypes.get(typeName);
        }
        if (packageName != null && !packageName.isBlank()) {
            if (JAVA_LANG_TYPES.contains(typeName)) {
                return "java.lang." + typeName;
            }
            return packageName + "." + typeName;
        }
        return typeName;
    }
}
