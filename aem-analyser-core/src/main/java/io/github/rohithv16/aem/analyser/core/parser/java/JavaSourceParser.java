package io.github.rohithv16.aem.analyser.core.parser.java;

import io.github.rohithv16.aem.analyser.core.model.graph.node.JavaClassNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiComponentNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiOcdNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.SlingModelNode;
import io.github.rohithv16.aem.analyser.core.parser.java.visitor.AnnotationUtils;
import io.github.rohithv16.aem.analyser.core.parser.java.visitor.ComponentPropertyConsumptionVisitor;
import io.github.rohithv16.aem.analyser.core.parser.java.visitor.MetatypeOcdVisitor;
import io.github.rohithv16.aem.analyser.core.parser.java.visitor.OsgiComponentVisitor;
import io.github.rohithv16.aem.analyser.core.parser.java.visitor.SlingModelVisitor;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ClassExpr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * FEAT-PARSE-01..03 orchestrator: parses one Java file with JavaParser, extracts all
 * symbols and pending cross-boundary edges, then immediately releases the CompilationUnit AST.
 */
public final class JavaSourceParser {

    /**
     * Parses at the Java 21 language level. Without this the parser silently rejects
     * modern syntax (switch expressions, records, pattern matching), dropping entire
     * files — and every component declared in them — out of the graph.
     */
    private static final ParserConfiguration PARSER_CONFIGURATION = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    private final MetatypeOcdVisitor ocdVisitor = new MetatypeOcdVisitor();
    private final OsgiComponentVisitor componentVisitor = new OsgiComponentVisitor();
    private final SlingModelVisitor slingModelVisitor = new SlingModelVisitor();
    private final io.github.rohithv16.aem.analyser.core.parser.java.visitor.CodeBlockVisitor codeBlockVisitor =
            new io.github.rohithv16.aem.analyser.core.parser.java.visitor.CodeBlockVisitor();
    private final io.github.rohithv16.aem.analyser.core.parser.java.visitor.ResourceLeakVisitor leakVisitor =
            new io.github.rohithv16.aem.analyser.core.parser.java.visitor.ResourceLeakVisitor();

    /** Project-wide {@code static final String} constants used to fold annotation values. */
    private Map<String, String> projectConstants = Map.of();

    public void setProjectConstants(Map<String, String> constants) {
        this.projectConstants = constants == null ? Map.of() : Map.copyOf(constants);
    }

    public JavaSourceParseResult parse(Path filePath) {
        AnnotationUtils.setProjectConstants(projectConstants);
        try {
            return parseInternal(filePath, false);
        } finally {
            AnnotationUtils.clearProjectConstants();
        }
    }

    /**
     * Parses a file and fails loudly on syntax/read errors so the orchestration layer can record a
     * diagnostic instead of silently losing every symbol in the file.
     */
    public JavaSourceParseResult parseStrict(Path filePath) {
        AnnotationUtils.setProjectConstants(projectConstants);
        try {
            return parseInternal(filePath, true);
        } finally {
            AnnotationUtils.clearProjectConstants();
        }
    }

    private JavaSourceParseResult parseInternal(Path filePath, boolean strict) {
        CompilationUnit cu;
        try {
            ParseResult<CompilationUnit> result = new JavaParser(PARSER_CONFIGURATION)
                    .parse(Files.newBufferedReader(filePath));
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                if (strict) {
                    throw new IllegalArgumentException("Java parse failed: " + result.getProblems());
                }
                return emptyResult(filePath);
            }
            cu = result.getResult().get();
        } catch (IOException e) {
            if (strict) {
                throw new UncheckedIOException(e);
            }
            return emptyResult(filePath);
        }

        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        Map<String, String> imports = AnnotationUtils.buildTypeResolver(
                packageName, cu.getImports());

        List<JavaClassNode> javaClasses = new ArrayList<>();
        List<OSGiComponentNode> components = new ArrayList<>();
        List<OSGiOcdNode> ocds = new ArrayList<>();
        List<SlingModelNode> slingModels = new ArrayList<>();
        List<PendingConsumption> pendingConsumptions = new ArrayList<>();
        List<PendingServiceInjection> pendingServiceInjections = new ArrayList<>();
        List<PendingAdaptTo> pendingAdaptTo = new ArrayList<>();
        Set<String> exportedPackages = new LinkedHashSet<>();

        // package-info.java with @Export
        if (filePath.getFileName().toString().equals("package-info.java")
                && cu.getPackageDeclaration().isPresent()
                && cu.getPackageDeclaration().get().getAnnotations().stream()
                .anyMatch(a -> AnnotationUtils.isAnnotation(a, "Export"))) {
            exportedPackages.add(packageName);
        }

        Map<String, String> localTypes = new LinkedHashMap<>();
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            type.getFullyQualifiedName().ifPresent(fqn -> localTypes.put(type.getNameAsString(), fqn));
        }

        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            Optional<OSGiOcdNode> ocd = ocdVisitor.extract(type, filePath);
            if (ocd.isPresent()) {
                ocds.add(ocd.get());
                continue;
            }
            Optional<OSGiComponentNode> component = componentVisitor.extract(type, packageName, imports, localTypes, filePath);
            if (component.isPresent()) {
                components.add(component.get());
                OSGiComponentNode componentNode = component.get();
                pendingConsumptions.addAll(extractConsumptions(componentNode.getFqn(), type, packageName, imports, localTypes, filePath));
                componentNode.getReferences().forEach((field, target) ->
                        pendingServiceInjections.add(new PendingServiceInjection(
                                componentNode.getFqn(), target, false, filePath)));
                continue;
            }
            Optional<SlingModelNode> model = slingModelVisitor.extract(type, packageName, imports, localTypes, filePath);
            if (model.isPresent()) {
                SlingModelNode modelNode = model.get();
                slingModels.add(modelNode);
                pendingConsumptions.addAll(extractConsumptions(modelNode.getFqn(), type, packageName, imports, localTypes, filePath));
                modelNode.getInjectedServices().forEach(service ->
                        pendingServiceInjections.add(new PendingServiceInjection(
                                modelNode.getFqn(), service, true, filePath)));
                continue;
            }

            if (AnnotationUtils.findAnnotation(type.getAnnotations(), "Export").isPresent()) {
                exportedPackages.add(packageName);
            }
            JavaClassNode classNode = plainClassNode(type, packageName, imports, localTypes, filePath);
            javaClasses.add(classNode);
            pendingConsumptions.addAll(extractConsumptions(classNode.getFqn(), type, packageName, imports, localTypes, filePath));
            // A plain class can hold @Reference fields that the inheriting @Component actually
            // consumes (abstract servlet base classes). Emit them so the graph can attribute the
            // injection to the subclass instead of reporting the service as unused.
            componentVisitor.extractReferences(type, packageName, imports, localTypes)
                    .forEach((field, target) -> pendingServiceInjections.add(
                            new PendingServiceInjection(classNode.getFqn(), target, false, filePath)));
        }

        List<AdministrativeCall> administrativeCalls = new ArrayList<>();
        // adaptTo(Target.class) / ModelFactory.createModel scanning & administrative API calls
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (isAdministrativeCall(call)) {
                String sourceFqn = enclosingTypeFqn(call, cu);
                administrativeCalls.add(new AdministrativeCall(name, sourceFqn, filePath,
                        MetatypeOcdVisitor.sourceLocation(call, filePath)));
                continue;
            }
            if (!name.equals("adaptTo") && !name.equals("getModel") && !name.equals("createModel")) {
                continue;
            }
            List<ClassExpr> classArgs = call.getArguments().stream()
                    .filter(ClassExpr.class::isInstance)
                    .map(ClassExpr.class::cast)
                    .toList();
            if (classArgs.isEmpty() && name.equals("adaptTo") && !call.getArguments().isEmpty()) {
                continue;
            }
            for (ClassExpr classArg : classArgs) {
                String targetTypeName = classArg.getType().asString();
                String resolved = AnnotationUtils.resolveTypeName(targetTypeName, packageName, imports, localTypes);
                String sourceFqn = enclosingTypeFqn(call, cu);
                if (sourceFqn != null) {
                    pendingAdaptTo.add(new PendingAdaptTo(sourceFqn, resolved, filePath));
                }
            }
        }

        List<CodeBlockSnapshot> codeBlocks = codeBlockVisitor.extract(cu, filePath, packageName);
        var leakResult = leakVisitor.analyze(cu, filePath);
        var subserviceCalls = new io.github.rohithv16.aem.analyser.core.parser.java.visitor.SubserviceCallExtractor().extract(cu, packageName, filePath);
        int lineCount = cu.getRange().map(r -> r.end.line).orElse(0);

        return new JavaSourceParseResult(filePath.toAbsolutePath().normalize(), packageName,
                javaClasses, components, ocds, slingModels, pendingConsumptions,
                pendingServiceInjections, pendingAdaptTo, exportedPackages, codeBlocks, administrativeCalls,
                leakResult.leakFindings(), leakResult.traversalFindings(), subserviceCalls, lineCount);
    }

    private List<PendingConsumption> extractConsumptions(String fqn,
                                                         TypeDeclaration<?> type,
                                                         String packageName,
                                                         Map<String, String> imports,
                                                         Map<String, String> localTypes,
                                                         Path filePath) {
        ComponentPropertyConsumptionVisitor consumptionVisitor = new ComponentPropertyConsumptionVisitor(
                fqn, packageName, imports, localTypes, filePath);
        consumptionVisitor.scan(type);
        return new ArrayList<>(consumptionVisitor.getConsumptions());
    }

    private JavaClassNode plainClassNode(TypeDeclaration<?> type, String packageName,
                                         Map<String, String> imports,
                                         Map<String, String> localTypes,
                                         Path filePath) {
        EnumSet<JavaClassNode.ModifierKind> modifiers = EnumSet.noneOf(JavaClassNode.ModifierKind.class);
        if (type.isPublic()) {
            modifiers.add(JavaClassNode.ModifierKind.PUBLIC);
        }
        Set<String> interfaces = type.isClassOrInterfaceDeclaration()
                ? type.asClassOrInterfaceDeclaration().getImplementedTypes().stream()
                .map(t -> AnnotationUtils.resolveTypeName(t.getNameWithScope(), packageName, imports, localTypes))
                .collect(Collectors.toCollection(LinkedHashSet::new))
                : Set.of();
        String superClass = type.isClassOrInterfaceDeclaration()
                && type.asClassOrInterfaceDeclaration().getExtendedTypes().isNonEmpty()
                ? AnnotationUtils.resolveTypeName(
                type.asClassOrInterfaceDeclaration().getExtendedTypes().get(0).getNameWithScope(),
                packageName, imports, localTypes)
                : null;
        String fqn = type.getFullyQualifiedName().orElse(type.getNameAsString());
        JavaClassNode.Kind kind = JavaClassNode.Kind.CLASS;
        if (type.isAnnotationDeclaration()) {
            kind = JavaClassNode.Kind.ANNOTATION;
        } else if (type.isClassOrInterfaceDeclaration()
                && type.asClassOrInterfaceDeclaration().isInterface()) {
            kind = JavaClassNode.Kind.INTERFACE;
        } else if (type.isEnumDeclaration()) {
            kind = JavaClassNode.Kind.ENUM;
        }
        return new JavaClassNode(fqn, kind, modifiers, superClass, interfaces, filePath,
                MetatypeOcdVisitor.sourceLocation(type, filePath));
    }

    /**
     * Detects deprecated administrative access across API shapes, not only two method names:
     * explicit admin login/resolver methods and generic login/resolver calls whose credentials or
     * auth map use the {@code admin}/{@code anonymous} user.
     */
    private boolean isAdministrativeCall(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (name.equals("loginAdministrative") || name.equals("getAdministrativeResourceResolver")) {
            return true;
        }
        if (!name.equals("login") && !name.contains("ResourceResolver")) {
            return false;
        }
        String arguments = call.getArguments().toString().toLowerCase(java.util.Locale.ROOT);
        boolean adminIdentity = arguments.contains("\"admin\"") || arguments.contains("\"anonymous\"");
        boolean identityApi = arguments.contains("credentials") || arguments.contains("user")
                || arguments.contains("simplecredentials");
        return adminIdentity && identityApi;
    }

    private String enclosingTypeFqn(com.github.javaparser.ast.Node node, CompilationUnit cu) {
        var parent = node.getParentNode();
        while (parent.isPresent()) {
            var current = parent.get();
            if (current instanceof TypeDeclaration<?> type) {
                return type.getFullyQualifiedName().orElse(type.getNameAsString());
            }
            parent = current.getParentNode();
        }
        return cu.getPrimaryType()
                .flatMap(TypeDeclaration::getFullyQualifiedName)
                .orElse(null);
    }

    private JavaSourceParseResult emptyResult(Path filePath) {
        return new JavaSourceParseResult(filePath.toAbsolutePath().normalize(), "",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Set.of());
    }
}
