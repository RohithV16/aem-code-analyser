package io.github.rohithv16.aem.analyser.core.parser.java.visitor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.github.rohithv16.aem.analyser.core.model.cloud.ImmutablePathWrite;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts writes into {@code /apps} and {@code /libs} from one compilation unit.
 *
 * <p>A path alone is not a finding: {@code resolver.getResource("/apps/acme/config")} is how every
 * component reads its own configuration, so reporting the read would drown the rule. A write is
 * proven by a mutating call on the same chain ({@code getNode("/libs/x").setProperty(...)},
 * {@code getResource("/apps/x").adaptTo(ModifiableValueMap.class).put(...)}) or, for the common
 * two-statement shape, by a mutating call on the variable the path was stored in inside the same
 * method. Paths assembled from a constant are resolved the same way the subservice extractor does
 * it, so {@code CURSOR_PATH = "/var/..."} is compared as its literal value.
 */
public final class ImmutablePathWriteExtractor {

    /** Calls whose first argument is a repository path. */
    private static final Set<String> PATH_CALLS = Set.of(
            "getResource", "getNode", "getNodeOrNull", "resolve");

    /** Calls that mutate the node or value map they are invoked on. */
    private static final Set<String> WRITE_CALLS = Set.of(
            "setProperty", "setValue", "put", "putAll", "remove", "removeProperty",
            "delete", "save", "commit", "addNode", "setPrimaryType", "addMixin");

    /** Immutable roots for AEM as a Cloud Service (repository structure documentation). */
    private static final Set<String> IMMUTABLE_ROOTS = Set.of("/apps", "/libs");

    public List<ImmutablePathWrite> extract(CompilationUnit cu, Path filePath) {
        List<ImmutablePathWrite> results = new ArrayList<>();
        if (cu == null) {
            return results;
        }
        Map<String, String> constants = collectLocalConstants(cu);
        Set<String> reported = new LinkedHashSet<>();

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            String operation = null;
            String path = null;

            if (PATH_CALLS.contains(name) && !call.getArguments().isEmpty()) {
                path = resolveString(call.getArgument(0), constants);
                if (!isImmutable(path)) {
                    continue;
                }
                operation = enclosingWrite(call).orElseGet(() -> variableWrite(call).orElse(null));
            } else if ("addNode".equals(name) && call.getArguments().size() == 1
                    && call.getScope().map(Expression::toString).orElse("").endsWith("getRootNode()")) {
                // session.getRootNode().addNode("apps") writes to the root-relative path.
                String child = resolveString(call.getArgument(0), constants);
                path = child == null || child.startsWith("/") ? child : "/" + child;
                if (!isImmutable(path)) {
                    continue;
                }
                operation = "addNode";
            }

            if (path == null || operation == null) {
                continue;
            }
            var nameRange = call.getName().getRange();
            int line = nameRange.map(range -> range.begin.line).orElse(1);
            int column = nameRange.map(range -> range.begin.column).orElse(1);
            if (reported.add(line + ":" + path)) {
                results.add(new ImmutablePathWrite(path, operation, filePath, line, column));
            }
        }
        return results;
    }

    /** Class- and method-level {@code String} constants, so a named path folds to its literal. */
    private static Map<String, String> collectLocalConstants(CompilationUnit cu) {
        Map<String, String> constants = new HashMap<>();
        for (VariableDeclarator declarator : cu.findAll(VariableDeclarator.class)) {
            declarator.getInitializer().ifPresent(initializer -> {
                if (initializer instanceof StringLiteralExpr literal) {
                    constants.put(declarator.getNameAsString(), literal.getValue());
                }
            });
        }
        for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
            field.getVariables().forEach(variable -> variable.getInitializer()
                    .filter(StringLiteralExpr.class::isInstance)
                    .ifPresent(initializer -> constants.put(variable.getNameAsString(),
                            initializer.asStringLiteralExpr().getValue())));
        }
        return constants;
    }

    /**
     * The mutating call that consumes this path on the same statement, e.g. the {@code put} of
     * {@code getResource("/apps/x").adaptTo(ModifiableValueMap.class).put("k", 1)}.
     */
    private static Optional<String> enclosingWrite(MethodCallExpr call) {
        Node current = call.getParentNode().orElse(null);
        while (current instanceof MethodCallExpr parent) {
            if (WRITE_CALLS.contains(parent.getNameAsString())) {
                return Optional.of(parent.getNameAsString());
            }
            current = parent.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    /**
     * The two-statement shape: {@code Node n = session.getNode("/libs/x"); n.setProperty(...)}.
     * Only a declarator initializer qualifies, so an unrelated call on a same-named variable in
     * another scope cannot be attributed to this path.
     */
    private static Optional<String> variableWrite(MethodCallExpr call) {
        Optional<VariableDeclarator> declarator = call.findAncestor(VariableDeclarator.class)
                .filter(candidate -> candidate.getInitializer().map(init -> init == call
                        || init.findAll(MethodCallExpr.class).contains(call)).orElse(false));
        if (declarator.isEmpty()) {
            return Optional.empty();
        }
        String variable = declarator.get().getNameAsString();
        Node scope = enclosingMethod(call).orElse(null);
        if (scope == null) {
            return Optional.empty();
        }
        for (MethodCallExpr candidate : scope.findAll(MethodCallExpr.class)) {
            if (candidate == call) {
                continue;
            }
            Optional<Expression> receiver = candidate.getScope();
            if (receiver.isPresent() && receiver.get() instanceof NameExpr nameExpr
                    && nameExpr.getNameAsString().equals(variable)
                    && WRITE_CALLS.contains(candidate.getNameAsString())) {
                return Optional.of(candidate.getNameAsString());
            }
        }
        return Optional.empty();
    }

    private static Optional<Node> enclosingMethod(MethodCallExpr call) {
        Optional<MethodDeclaration> method = call.findAncestor(MethodDeclaration.class);
        return method.map(Node.class::cast);
    }

    private static String resolveString(Expression expression, Map<String, String> constants) {
        if (expression instanceof StringLiteralExpr literal) {
            return literal.getValue();
        }
        if (expression instanceof NameExpr nameExpr) {
            return constants.get(nameExpr.getNameAsString());
        }
        if (expression.isFieldAccessExpr()) {
            return constants.get(expression.asFieldAccessExpr().getNameAsString());
        }
        return null;
    }

    /** True for {@code /apps}, {@code /libs} and anything below them; {@code /apps_old} is not. */
    private static boolean isImmutable(String path) {
        if (path == null || !path.startsWith("/")) {
            return false;
        }
        for (String root : IMMUTABLE_ROOTS) {
            if (path.equals(root) || path.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }
}
