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
        Map<String, String> localConstants = collectLocalConstants(cu);
        Set<String> factoryReceivers = collectFactoryReceivers(cu);
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            SubserviceCall subserviceCall = toSubserviceCall(call, classFqn, filePath,
                    localConstants, factoryReceivers);
            if (subserviceCall != null) {
                results.add(subserviceCall);
            }
        }
        return results;
    }

    /** Class-level constant string values, so {@code SUB_SERVICE} resolves to its literal. */
    private static Map<String, String> collectLocalConstants(CompilationUnit cu) {
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
        return localConstants;
    }

    /** @return the acquisition call as a finding, or {@code null} when it is not one */
    private SubserviceCall toSubserviceCall(MethodCallExpr call, String classFqn, Path filePath,
                                            Map<String, String> localConstants,
                                            Set<String> factoryReceivers) {
        if (!isAcquisitionCall(call, factoryReceivers) || call.getArguments().isEmpty()) {
            return null;
        }
        String name = call.getNameAsString();
        Expression argument = call.getArgument(0);
        boolean loginVariant = name.equals("loginService") || name.equals("loginAdministrative");
        String subservice = null;
        SubserviceCall.Resolution resolution;
        if (argument.isNullLiteralExpr()) {
            resolution = SubserviceCall.Resolution.NULL;
        } else if (loginVariant) {
            subservice = resolveSubserviceString(argument, localConstants);
            resolution = resolutionOf(subservice);
        } else {
            subservice = resolveFromMapExpression(argument, call, localConstants);
            resolution = resolutionOf(subservice);
        }

        var nameRange = call.getName().getRange();
        int line = nameRange.map(r -> r.begin.line).orElse(1);
        int column = nameRange.map(r -> r.begin.column).orElse(1);
        return new SubserviceCall(subservice, classFqn, filePath, line, column, resolution);
    }

    private static SubserviceCall.Resolution resolutionOf(String subservice) {
        return subservice != null
                ? SubserviceCall.Resolution.LITERAL
                : SubserviceCall.Resolution.UNRESOLVED;
    }

    private String resolveFromMapExpression(Expression mapExpr, MethodCallExpr call,
                                            Map<String, String> localConstants) {
        // Direct Map.of(...) or Collections.singletonMap(...)
        String direct = extractStringFromMapCall(mapExpr, localConstants);
        if (direct != null || !mapExpr.isNameExpr()) {
            return direct;
        }
        // Variable reference (e.g. param, authInfo)
        return resolveFromEnclosingMethod(mapExpr.asNameExpr().getNameAsString(), call, localConstants);
    }

    /** Looks for the map variable's initializer, then for {@code put("subservice", ...)} calls. */
    private String resolveFromEnclosingMethod(String varName, MethodCallExpr call,
                                              Map<String, String> localConstants) {
        MethodDeclaration enclosingMethod = findEnclosingMethod(call);
        if (enclosingMethod == null) {
            return null;
        }
        String declared = resolveFromDeclaration(varName, enclosingMethod, localConstants);
        return declared != null ? declared : resolveFromPutCalls(enclosingMethod, localConstants);
    }

    private String resolveFromDeclaration(String varName, MethodDeclaration enclosingMethod,
                                          Map<String, String> localConstants) {
        for (VariableDeclarator declarator : enclosingMethod.findAll(VariableDeclarator.class)) {
            if (declarator.getNameAsString().equals(varName) && declarator.getInitializer().isPresent()) {
                String value = extractStringFromMapCall(declarator.getInitializer().get(), localConstants);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private String resolveFromPutCalls(MethodDeclaration enclosingMethod,
                                       Map<String, String> localConstants) {
        for (MethodCallExpr methodCall : enclosingMethod.findAll(MethodCallExpr.class)) {
            if (methodCall.getNameAsString().equals("put") && methodCall.getArguments().size() >= 2
                    && isSubserviceKey(methodCall.getArgument(0))) {
                return resolveSubserviceString(methodCall.getArgument(1), localConstants);
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
            String value = extractFromMapCall(mce, localConstants);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String extractFromMapCall(MethodCallExpr mce, Map<String, String> localConstants) {
        String name = mce.getNameAsString();
        var args = mce.getArguments();
        if ((name.equals("entry") || name.equals("put")) && args.size() >= 2
                && isSubserviceKey(args.get(0))) {
            return resolveSubserviceString(args.get(1), localConstants);
        }
        if (!name.equals("of") && !name.equals("singletonMap")) {
            return null;
        }
        String paired = extractFromKeyValuePairs(args, localConstants);
        if (paired != null || args.size() != 2) {
            return paired;
        }
        return resolveSubserviceString(args.get(1), localConstants);
    }

    /** Scans {@code key, value, key, value, ...} arguments of a map factory. */
    private String extractFromKeyValuePairs(List<Expression> args,
                                            Map<String, String> localConstants) {
        for (int i = 0; i + 1 < args.size(); i += 2) {
            if (isSubserviceKey(args.get(i))) {
                String value = resolveSubserviceString(args.get(i + 1), localConstants);
                if (value != null) {
                    return value;
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
