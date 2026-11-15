package io.github.rohithv16.aem.analyser.core.parser.java.visitor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Precision AST extractor for getServiceResourceResolver(...) and loginService(...) subservice usages.
 */
public final class SubserviceCallExtractor {

    public List<SubserviceCall> extract(CompilationUnit cu, String classFqn, Path filePath) {
        List<SubserviceCall> results = new ArrayList<>();
        if (cu == null) {
            return results;
        }

        // Collect class-level constant string values
        Map<String, String> localConstants = new HashMap<>();
        for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator var : field.getVariables()) {
                var.getInitializer().ifPresent(init -> {
                    if (init.isStringLiteralExpr()) {
                        localConstants.put(var.getNameAsString(), init.asStringLiteralExpr().getValue());
                    }
                });
            }
        }

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (!name.equals("getServiceResourceResolver") && !name.equals("loginService")) {
                continue;
            }

            String subservice = null;
            if (name.equals("loginService") && !call.getArguments().isEmpty()) {
                subservice = resolveSubserviceString(call.getArgument(0), localConstants);
            } else if (name.equals("getServiceResourceResolver") && !call.getArguments().isEmpty()) {
                Expression arg = call.getArgument(0);
                subservice = resolveFromMapExpression(arg, call, localConstants);
            }

            int line = call.getRange().map(r -> r.begin.line).orElse(1);
            int col = call.getRange().map(r -> r.begin.column).orElse(1);
            results.add(new SubserviceCall(subservice, classFqn, filePath, line, col));
        }

        return results;
    }

    private String resolveFromMapExpression(Expression mapExpr, MethodCallExpr call, Map<String, String> localConstants) {
        // Direct Map.of(...) or Collections.singletonMap(...)
        String direct = extractStringFromMapCall(mapExpr, localConstants);
        if (direct != null) {
            return direct;
        }

        // Variable reference (e.g. param, authInfo)
        if (mapExpr.isNameExpr()) {
            String varName = mapExpr.asNameExpr().getNameAsString();
            // Look in enclosing method for assignments/initializations of varName
            MethodDeclaration enclosingMethod = findEnclosingMethod(call);
            if (enclosingMethod != null) {
                for (VariableDeclarator vd : enclosingMethod.findAll(VariableDeclarator.class)) {
                    if (vd.getNameAsString().equals(varName) && vd.getInitializer().isPresent()) {
                        String val = extractStringFromMapCall(vd.getInitializer().get(), localConstants);
                        if (val != null) {
                            return val;
                        }
                    }
                }
                for (MethodCallExpr methodCall : enclosingMethod.findAll(MethodCallExpr.class)) {
                    if (methodCall.getNameAsString().equals("put") && methodCall.getArguments().size() >= 2) {
                        String key = methodCall.getArgument(0).toString();
                        if (key.contains("SUBSERVICE") || key.contains("subservice")) {
                            return resolveSubserviceString(methodCall.getArgument(1), localConstants);
                        }
                    }
                }
            }
        }

        return null;
    }

    private String extractStringFromMapCall(Expression expr, Map<String, String> localConstants) {
        if (!expr.isMethodCallExpr()) {
            return null;
        }
        MethodCallExpr mce = expr.asMethodCallExpr();
        String name = mce.getNameAsString();
        if (name.equals("singletonMap") || name.equals("of")) {
            var args = mce.getArguments();
            for (int i = 0; i < args.size() - 1; i += 2) {
                Expression keyExpr = args.get(i);
                Expression valExpr = args.get(i + 1);
                String keyStr = keyExpr.toString();
                if (keyStr.contains("SUBSERVICE") || keyStr.contains("subservice")) {
                    return resolveSubserviceString(valExpr, localConstants);
                }
            }
            if (args.size() == 2) {
                return resolveSubserviceString(args.get(1), localConstants);
            }
        }
        return null;
    }

    private String resolveSubserviceString(Expression expr, Map<String, String> localConstants) {
        if (expr.isStringLiteralExpr()) {
            return expr.asStringLiteralExpr().getValue();
        }
        if (expr.isNameExpr()) {
            String id = expr.asNameExpr().getNameAsString();
            if (localConstants.containsKey(id)) {
                return localConstants.get(id);
            }
            return id;
        }
        if (expr.isFieldAccessExpr()) {
            FieldAccessExpr fae = expr.asFieldAccessExpr();
            return fae.getNameAsString();
        }
        return null;
    }

    private MethodDeclaration findEnclosingMethod(MethodCallExpr call) {
        com.github.javaparser.ast.Node current = call.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof MethodDeclaration md) {
                return md;
            }
            current = current.getParentNode().orElse(null);
        }
        return null;
    }
}
