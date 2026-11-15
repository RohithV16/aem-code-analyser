package io.github.aemtools.analyser.core.parser.java.visitor;

import io.github.aemtools.analyser.core.model.graph.SourceLocation;
import io.github.aemtools.analyser.core.parser.java.PendingConsumption;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FEAT-PARSE-01b: extracts {@code CONSUMES_PROPERTY} pending edges on-the-fly by locating
 * direct method calls and field member accesses on injected configuration objects.
 * The CompilationUnit AST is dereferenced by the caller immediately after visiting.
 */
public final class ComponentPropertyConsumptionVisitor extends VoidVisitorAdapter<Void> {

    private final String componentFqn;
    private final String packageName;
    private final Map<String, String> imports;
    private final Map<String, String> localTypes;
    private final Path filePath;
    /** receiver name -> resolved declared type */
    private final Map<String, String> receiverTypes = new LinkedHashMap<>();
    private final Set<String> constructorParameterNames = new LinkedHashSet<>();
    private final Set<PendingConsumption> consumptions = new LinkedHashSet<>();

    public ComponentPropertyConsumptionVisitor(String componentFqn,
                                               String packageName,
                                               Map<String, String> imports,
                                               Path filePath) {
        this(componentFqn, packageName, imports, Map.of(), filePath);
    }

    public ComponentPropertyConsumptionVisitor(String componentFqn,
                                               String packageName,
                                               Map<String, String> imports,
                                               Map<String, String> localTypes,
                                               Path filePath) {
        this.componentFqn = componentFqn;
        this.packageName = packageName;
        this.imports = imports;
        this.localTypes = localTypes != null ? localTypes : Map.of();
        this.filePath = filePath;
    }

    /** Entry point: walks the component type body with generic dispatch. */
    public void scan(TypeDeclaration<?> type) {
        type.accept(this, null);
    }

    @Override
    public void visit(FieldDeclaration field, Void arg) {
        field.getVariables().forEach(variable -> {
            String resolved = AnnotationUtils.resolveTypeName(
                    AnnotationUtils.elementType(variable.getType().asString()), packageName, imports, localTypes);
            receiverTypes.put(variable.getNameAsString(), resolved);
        });
        super.visit(field, arg);
    }

    @Override
    public void visit(ConstructorDeclaration constructor, Void arg) {
        constructor.getParameters().forEach(this::recordParameter);
        super.visit(constructor, arg);
    }

    @Override
    public void visit(MethodDeclaration method, Void arg) {
        method.getParameters().forEach(this::recordParameter);
        super.visit(method, arg);
    }

    @Override
    public void visit(com.github.javaparser.ast.expr.VariableDeclarationExpr varExpr, Void arg) {
        varExpr.getVariables().forEach(variable -> {
            String resolved = AnnotationUtils.resolveTypeName(
                    AnnotationUtils.elementType(variable.getType().asString()), packageName, imports, localTypes);
            receiverTypes.put(variable.getNameAsString(), resolved);
        });
        super.visit(varExpr, arg);
    }

    private void recordParameter(Parameter parameter) {
        String resolved = AnnotationUtils.resolveTypeName(
                AnnotationUtils.elementType(parameter.getType().asString()), packageName, imports, localTypes);
        receiverTypes.put(parameter.getNameAsString(), resolved);
        constructorParameterNames.add(parameter.getNameAsString());
    }

    @Override
    public void visit(MethodCallExpr call, Void arg) {
        Optional<com.github.javaparser.ast.expr.Expression> scope = call.getScope();
        if (scope.isPresent()) {
            String receiverName = receiverNameOf(scope.get());
            if (receiverName != null && receiverTypes.containsKey(receiverName)) {
                String resolvedType = receiverTypes.get(receiverName);
                PendingConsumption.InvocationType invocationType = constructorParameterNames.contains(receiverName)
                        ? PendingConsumption.InvocationType.CONSTRUCTOR_INJECTION
                        : PendingConsumption.InvocationType.FIELD_MEMBER_ACCESS;
                SourceLocation callSite = call.getBegin()
                        .map(p -> new SourceLocation(filePath, p.line, p.column))
                        .orElse(SourceLocation.wholeFile(filePath));
                consumptions.add(new PendingConsumption(
                        componentFqn,
                        resolvedType,
                        call.getNameAsString(),
                        filePath,
                        callSite,
                        Optional.of(invocationType.name()),
                        enclosingMethodName(call)));
            }
        }
        super.visit(call, arg);
    }

    private String receiverNameOf(com.github.javaparser.ast.expr.Expression scope) {
        if (scope instanceof NameExpr nameExpr) {
            return nameExpr.getNameAsString();
        }
        if (scope instanceof FieldAccessExpr fieldAccess
                && fieldAccess.getScope() instanceof NameExpr base
                && base.getNameAsString().equals("this")) {
            return fieldAccess.getNameAsString();
        }
        return null;
    }

    private String enclosingMethodName(MethodCallExpr call) {
        var parent = call.getParentNode();
        while (parent.isPresent()) {
            var node = parent.get();
            if (node instanceof MethodDeclaration method) {
                return method.getNameAsString();
            }
            if (node instanceof ConstructorDeclaration constructor) {
                return constructor.getNameAsString();
            }
            if (node instanceof com.github.javaparser.ast.stmt.BlockStmt
                    || node instanceof com.github.javaparser.ast.stmt.Statement
                    || node instanceof com.github.javaparser.ast.expr.Expression) {
                parent = node.getParentNode();
                continue;
            }
            break;
        }
        return "<initializer>";
    }

    public Set<PendingConsumption> getConsumptions() {
        return Set.copyOf(consumptions);
    }
}
