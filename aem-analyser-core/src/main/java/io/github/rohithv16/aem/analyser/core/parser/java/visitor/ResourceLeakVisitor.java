package io.github.rohithv16.aem.analyser.core.parser.java.visitor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import io.github.rohithv16.aem.analyser.core.model.perf.QueryTraversalFinding;
import io.github.rohithv16.aem.analyser.core.model.perf.ResourceLeakFinding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AST visitor detecting unclosed ResourceResolvers, unclosed JCR Sessions,
 * and high-risk Oak query repository traversal patterns.
 */
public final class ResourceLeakVisitor {

    private static final Set<String> RESOLVER_FACTORY_METHODS = Set.of(
            "getServiceResourceResolver",
            "getResourceResolver",
            "getAdministrativeResourceResolver"
    );

    private static final Set<String> SESSION_LOGIN_METHODS = Set.of(
            "loginService",
            "loginAdministrative",
            "login",
            "loginByToken",
            "impersonate"
    );

    public record LeakAnalysisResult(
            List<ResourceLeakFinding> leakFindings,
            List<QueryTraversalFinding> traversalFindings
    ) {}

    public LeakAnalysisResult analyze(CompilationUnit cu, Path filePath) {
        List<ResourceLeakFinding> leaks = new ArrayList<>();
        List<QueryTraversalFinding> traversals = new ArrayList<>();
        Set<String> factoryReceivers = collectReceivers(cu, "ResourceResolverFactory");
        Set<String> repositoryReceivers = collectReceivers(cu, "Repository");

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();

            // 1. ResourceResolver Factory call (only calls made through a factory, never borrowed
            //    resolvers such as asset.getResourceResolver())
            if (isResourceResolverFactoryCall(call, name, factoryReceivers)) {
                if (!isInsideTryWithResources(call) && !isDirectlyReturned(call)) {
                    String varName = findAssignedVariableName(call);
                    Node enclosingScope = findEnclosingScope(call);
                    if (varName == null || enclosingScope == null || (!isClosedInScope(enclosingScope, varName, "close") && !isReturnedFromMethod(enclosingScope, varName))) {
                        int line = call.getRange().map(r -> r.begin.line).orElse(1);
                        int col = call.getRange().map(r -> r.begin.column).orElse(1);
                        leaks.add(new ResourceLeakFinding(
                                filePath, line, col, varName, name, ResourceLeakFinding.LeakKind.RESOURCE_RESOLVER
                        ));
                    }
                }
            }

            // 2. JCR Session login call
            if (SESSION_LOGIN_METHODS.contains(name) && isLoginReceiver(call, name, repositoryReceivers)) {
                if (!isInsideTryWithResources(call) && !isDirectlyReturned(call)) {
                    String varName = findAssignedVariableName(call);
                    Node enclosingScope = findEnclosingScope(call);
                    if (varName == null || enclosingScope == null || (!isClosedInScope(enclosingScope, varName, "logout") && !isClosedInScope(enclosingScope, varName, "close") && !isReturnedFromMethod(enclosingScope, varName))) {
                        int line = call.getRange().map(r -> r.begin.line).orElse(1);
                        int col = call.getRange().map(r -> r.begin.column).orElse(1);
                        leaks.add(new ResourceLeakFinding(
                                filePath, line, col, varName, name, ResourceLeakFinding.LeakKind.JCR_SESSION
                        ));
                    }
                }
            }

            // 3. adaptTo(Session.class)
            if (name.equals("adaptTo") && call.getArguments().size() == 1) {
                Expression arg = call.getArgument(0);
                if (arg instanceof ClassExpr classExpr) {
                    String adaptedType = classExpr.getType().asString();
                    boolean sessionType = adaptedType.equals("Session") || adaptedType.endsWith(".Session");
                    if (sessionType) {
                    if (!isInsideTryWithResources(call) && !isDirectlyReturned(call)
                            && !isScopeManagedByTryWithResources(call)
                            && !isBorrowedReceiver(call, factoryReceivers)) {
                        String varName = findAssignedVariableName(call);
                        Node enclosingScope = findEnclosingScope(call);
                        if (varName == null || enclosingScope == null || (!isClosedInScope(enclosingScope, varName, "logout") && !isClosedInScope(enclosingScope, varName, "close") && !isReturnedFromMethod(enclosingScope, varName))) {
                            int line = call.getRange().map(r -> r.begin.line).orElse(1);
                            int col = call.getRange().map(r -> r.begin.column).orElse(1);
                            leaks.add(new ResourceLeakFinding(
                                    filePath, line, col, varName, "adaptTo(Session.class)", ResourceLeakFinding.LeakKind.JCR_SESSION
                            ));
                        }
                    }
                }
                }
            }

            // 4. Query Traversal Risks: findResources(query, lang) or createQuery(query, lang)
            if (name.equals("findResources") || name.equals("createQuery")) {
                if (!call.getArguments().isEmpty() && call.getArgument(0) instanceof StringLiteralExpr strExpr) {
                    String query = strExpr.getValue();
                    String queryLower = query.toLowerCase(Locale.ROOT);
                    if (queryLower.contains("nt:base") && !queryLower.contains("where")) {
                        int line = call.getRange().map(r -> r.begin.line).orElse(1);
                        int col = call.getRange().map(r -> r.begin.column).orElse(1);
                        String lang = call.getArguments().size() > 1 && call.getArgument(1) instanceof StringLiteralExpr l
                                ? l.getValue() : "JCR-SQL2";
                        traversals.add(new QueryTraversalFinding(filePath, line, col, query, lang));
                    }
                }
            }
        }

        return new LeakAnalysisResult(leaks, traversals);
    }

    private boolean isInsideTryWithResources(Node node) {
        Node current = node;
        while (current != null) {
            Node parent = current.getParentNode().orElse(null);
            if (parent instanceof TryStmt tryStmt) {
                for (Expression res : tryStmt.getResources()) {
                    if (res == current || res.isAncestorOf(node)) {
                        return true;
                    }
                }
            }
            current = parent;
        }
        return false;
    }

    /**
     * True when the receiver of this call is itself a try-with-resources resource, e.g.
     * {@code try (ResourceResolver resolver = ...) { resolver.adaptTo(Session.class); }}.
     * Closing the resolver releases the adapted session, so this is not a leak.
     */
    private boolean isScopeManagedByTryWithResources(MethodCallExpr call) {
        String scope = call.getScope().map(Expression::toString).orElse("");
        if (scope.isBlank()) {
            return false;
        }
        Node current = call;
        while (current != null) {
            Node parent = current.getParentNode().orElse(null);
            if (parent instanceof TryStmt tryStmt) {
                for (Expression resource : tryStmt.getResources()) {
                    String text = resource.toString();
                    if (text.contains(scope + " =") || text.contains(scope + "=")
                            || text.startsWith(scope + " ") || text.equals(scope)
                            || resource.isAncestorOf(call)) {
                        return true;
                    }
                }
            }
            current = parent;
        }
        return false;
    }

    /**
     * True when the adapted receiver is not owned by this method: a parameter or a field. Closing a
     * session adapted from a caller-owned resolver would be wrong, and closing the resolver releases
     * the session anyway.
     */
    private boolean isBorrowedReceiver(MethodCallExpr call, Set<String> factoryReceivers) {
        String scope = call.getScope().map(Expression::toString).orElse("");
        if (scope.isBlank()) {
            return false;
        }
        MethodDeclaration method = findEnclosingMethod(call);
        if (method == null) {
            return false;
        }
        for (com.github.javaparser.ast.body.Parameter parameter : method.getParameters()) {
            if (parameter.getNameAsString().equals(scope)) {
                return true;
            }
        }
        for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
            if (variable.getNameAsString().equals(scope) && variable.getInitializer().isPresent()) {
                Expression initializer = variable.getInitializer().get();
                if (initializer.isMethodCallExpr()) {
                    MethodCallExpr acquisition = initializer.asMethodCallExpr();
                    // Only a factory acquisition transfers ownership; request/resource/asset
                    // resolvers are borrowed and must not have their session closed.
                    return !isResourceResolverFactoryCall(acquisition, acquisition.getNameAsString(), factoryReceivers);
                }
                return false;
            }
        }
        return !method.findAll(VariableDeclarator.class).stream()
                .anyMatch(variable -> variable.getNameAsString().equals(scope)); // field/outer scope
    }

    private MethodDeclaration findEnclosingMethod(MethodCallExpr call) {
        Node current = call.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof MethodDeclaration method) {
                return method;
            }
            current = current.getParentNode().orElse(null);
        }
        return null;
    }

    /**
     * Distinguishes a resolver acquired from a {@code ResourceResolverFactory} from a borrowed
     * resolver such as {@code asset.getResourceResolver()}. Only the former must be closed.
     */
    private boolean isResourceResolverFactoryCall(MethodCallExpr call, String name,
                                                  Set<String> factoryReceivers) {
        if (!RESOLVER_FACTORY_METHODS.contains(name)) {
            return false;
        }
        if (name.equals("getServiceResourceResolver") || name.equals("getAdministrativeResourceResolver")) {
            return true;
        }
        String scopeText = call.getScope().map(Expression::toString).orElse("");
        String simpleScope = call.getScope().filter(Expression::isNameExpr)
                .map(expression -> expression.asNameExpr().getNameAsString())
                .orElse(scopeText);
        String lower = scopeText.toLowerCase(Locale.ROOT);
        return factoryReceivers.contains(simpleScope)
                || lower.contains("factory")
                || lower.contains("repository");
    }

    private boolean isLoginReceiver(MethodCallExpr call, String name, Set<String> repositoryReceivers) {
        if (!name.equals("login") && !name.equals("loginByToken")) {
            return true; // loginService/loginAdministrative/impersonate are unambiguous APIs
        }
        String scopeText = call.getScope().map(Expression::toString).orElse("");
        String simpleScope = call.getScope().filter(Expression::isNameExpr)
                .map(expression -> expression.asNameExpr().getNameAsString())
                .orElse(scopeText);
        return repositoryReceivers.contains(simpleScope)
                || scopeText.toLowerCase(Locale.ROOT).contains("repository");
    }

    private Set<String> collectReceivers(CompilationUnit cu, String typeSuffix) {
        Set<String> names = new HashSet<>();
        for (com.github.javaparser.ast.body.FieldDeclaration field : cu.findAll(com.github.javaparser.ast.body.FieldDeclaration.class)) {
            if (field.getElementType().asString().contains(typeSuffix)) {
                field.getVariables().forEach(variable -> names.add(variable.getNameAsString()));
            }
        }
        for (com.github.javaparser.ast.body.Parameter parameter : cu.findAll(com.github.javaparser.ast.body.Parameter.class)) {
            if (parameter.getType().asString().contains(typeSuffix)) {
                names.add(parameter.getNameAsString());
            }
        }
        for (VariableDeclarator variable : cu.findAll(VariableDeclarator.class)) {
            if (variable.getType().asString().contains(typeSuffix)) {
                names.add(variable.getNameAsString());
            }
        }
        return names;
    }

    private String findAssignedVariableName(MethodCallExpr call) {
        Node current = call;
        while (current != null) {
            if (current instanceof VariableDeclarator vd) {
                return vd.getNameAsString();
            }
            if (current instanceof com.github.javaparser.ast.expr.AssignExpr assignExpr) {
                return assignExpr.getTarget().toString();
            }
            current = current.getParentNode().orElse(null);
        }
        return null;
    }

    private Node findEnclosingScope(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof MethodDeclaration md) {
                return md;
            }
            current = current.getParentNode().orElse(null);
        }
        return null;
    }

    private boolean isClosedInScope(Node scope, String varName, String closeMethod) {
        for (MethodCallExpr call : scope.findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals(closeMethod)) {
                var scopeExpr = call.getScope();
                if (scopeExpr.isPresent() && scopeExpr.get().toString().equals(varName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isDirectlyReturned(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof com.github.javaparser.ast.stmt.ReturnStmt) {
                return true;
            }
            if (current instanceof MethodDeclaration) {
                break;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean isReturnedFromMethod(Node scope, String varName) {
        if (scope instanceof MethodDeclaration md && varName != null) {
            for (com.github.javaparser.ast.stmt.ReturnStmt ret : md.findAll(com.github.javaparser.ast.stmt.ReturnStmt.class)) {
                if (ret.getExpression().isPresent() && ret.getExpression().get().toString().equals(varName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
