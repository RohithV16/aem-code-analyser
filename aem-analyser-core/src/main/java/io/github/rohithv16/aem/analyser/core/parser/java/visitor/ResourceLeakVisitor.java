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
            "login"
    );

    public record LeakAnalysisResult(
            List<ResourceLeakFinding> leakFindings,
            List<QueryTraversalFinding> traversalFindings
    ) {}

    public LeakAnalysisResult analyze(CompilationUnit cu, Path filePath) {
        List<ResourceLeakFinding> leaks = new ArrayList<>();
        List<QueryTraversalFinding> traversals = new ArrayList<>();

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();

            // 1. ResourceResolver Factory call
            if (RESOLVER_FACTORY_METHODS.contains(name)) {
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
            if (SESSION_LOGIN_METHODS.contains(name)) {
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
                if (arg instanceof ClassExpr classExpr && classExpr.getType().asString().equals("Session")) {
                    if (!isInsideTryWithResources(call) && !isDirectlyReturned(call)) {
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
