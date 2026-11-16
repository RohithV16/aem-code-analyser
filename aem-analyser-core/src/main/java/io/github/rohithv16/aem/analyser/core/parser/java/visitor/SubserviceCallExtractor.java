package io.github.rohithv16.aem.analyser.core.parser.java.visitor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Precision AST extractor for service-resolver acquisitions with a subservice:
 * {@code ResourceResolverFactory.getServiceResourceResolver(Map)},
 * {@code ResourceResolverFactory.getResourceResolver(Map)} and
 * {@code SlingRepository.loginService(String, ...)} (plus legacy login variants).
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
            for (VariableDeclarator declarator : field.getVariables()) {
                declarator.getInitializer().ifPresent(init -> {
                    if (init.isStringLiteralExpr()) {
                        localConstants.put(declarator.getNameAsString(),
                                init.asStringLiteralExpr().getValue());
                    }
                });
            }
        }
        Set<String> factoryReceivers = collectFactoryReceivers(cu);

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (!isAcquisitionCall(call, factoryReceivers) || call.getArguments().isEmpty()) {
                continue;
            }

            String subservice = null;
            SubserviceCall.Resolution resolution;
            if (name.equals("loginService") || name.equals("loginAdministrative")) {
                Expression argument = call.getArgument(0);
                if (argument.isNullLiteralExpr()) {
                    resolution = SubserviceCall.Resolution.NULL;
                } else {
                    subservice = resolveSubserviceString(argument, localConstants);
                    resolution = subservice != null
                            ? SubserviceCall.Resolution.LITERAL
                            : SubserviceCall.Resolution.UNRESOLVED;
                }
            } else {
                Expression argument = call.getArgument(0);
                if (argument.isNullLiteralExpr()) {
                    resolution = SubserviceCall.Resolution.NULL;
                } else {
                    subservice = resolveFromMapExpression(argument, call, localConstants);
                    resolution = subservice != null
                            ? SubserviceCall.Resolution.LITERAL
                            : SubserviceCall.Resolution.UNRESOLVED;
                }
            }

            var nameRange = call.getName().getRange();
            int line = nameRange.map(r -> r.begin.line).orElse(1);
            int col = nameRange.map(r -> r.begin.column).orElse(1);
            results.add(new SubserviceCall(subservice, classFqn, filePath, line, col, resolution));
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
                        String key = methodCall.getArgument(0).toString().toLowerCase(java.util.Locale.ROOT);
                        if (key.contains("subservice")) {
                            return resolveSubserviceString(methodCall.getArgument(1), localConstants);
                        }
                    }
                }
            }
        }

        return null;
    }

    private String extractStringFromMapCall(Expression expr, Map<String, String> localConstants) {
        if (expr == null) {
            return null;
        }
        // Find the subservice entry in any map construction (Map.of, singletonMap, ofEntries/entry,
        // put, ImmutableMap.of, unmodifiableMap(...), ...), not just one API shape.
        for (MethodCallExpr mce : expr.findAll(MethodCallExpr.class)) {
            String name = mce.getNameAsString();
            var args = mce.getArguments();
            if ((name.equals("entry") || name.equals("put")) && args.size() >= 2
                    && isSubserviceKey(args.get(0))) {
                String value = resolveSubserviceString(args.get(1), localConstants);
                if (value != null) {
                    return value;
                }
            }
            if ((name.equals("of") || name.equals("singletonMap")) && args.size() >= 2) {
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    if (isSubserviceKey(args.get(i))) {
                        String value = resolveSubserviceString(args.get(i + 1), localConstants);
                        if (value != null) {
                            return value;
                        }
                    }
                }
                if (args.size() == 2) {
                    String value = resolveSubserviceString(args.get(1), localConstants);
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private boolean isSubserviceKey(Expression keyExpression) {
        return keyExpression.toString().toLowerCase(Locale.ROOT).contains("subservice");
    }

    private String resolveSubserviceString(Expression expr, Map<String, String> localConstants) {
        if (expr.isStringLiteralExpr()) {
            return expr.asStringLiteralExpr().getValue();
        }
        if (expr.isNameExpr()) {
            String id = expr.asNameExpr().getNameAsString();
            return localConstants.get(id); // null when unresolved: caller marks UNRESOLVED
        }
        if (expr.isFieldAccessExpr()) {
            FieldAccessExpr fae = expr.asFieldAccessExpr();
            // Constants declared in this file are resolvable even when referenced as Constants.X.
            return localConstants.get(fae.getNameAsString());
        }
        return null;
    }

    /**
     * A service-resolver acquisition is any factory/repository API that takes authentication info,
     * not a single hardcoded method name. {@code getResourceResolver(Map)} is only treated as a
     * factory call when the receiver is a factory/repository (so {@code asset.getResourceResolver()}
     * is not misclassified).
     */
    private boolean isAcquisitionCall(MethodCallExpr call, Set<String> factoryReceivers) {
        String name = call.getNameAsString();
        if (name.equals("loginService") || name.equals("loginAdministrative")
                || name.equals("getServiceResourceResolver")) {
            return true;
        }
        if (!name.equals("getResourceResolver")) {
            return false;
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

    private Set<String> collectFactoryReceivers(CompilationUnit cu) {
        Set<String> names = new HashSet<>();
        for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
            if (isFactoryType(field.getElementType().asString())) {
                field.getVariables().forEach(variable -> names.add(variable.getNameAsString()));
            }
        }
        for (com.github.javaparser.ast.body.Parameter parameter
                : cu.findAll(com.github.javaparser.ast.body.Parameter.class)) {
            if (isFactoryType(parameter.getType().asString())) {
                names.add(parameter.getNameAsString());
            }
        }
        for (VariableDeclarator variable : cu.findAll(VariableDeclarator.class)) {
            if (isFactoryType(variable.getType().asString())) {
                names.add(variable.getNameAsString());
            }
        }
        return names;
    }

    private boolean isFactoryType(String type) {
        return type.contains("ResourceResolverFactory") || type.contains("SlingRepository");
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
