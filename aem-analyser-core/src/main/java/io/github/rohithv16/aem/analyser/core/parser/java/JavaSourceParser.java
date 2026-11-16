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
        CompilationUnit cu = parseUnit(filePath, strict);
        if (cu == null) {
            return emptyResult(filePath);
        }
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        Map<String, String> imports = AnnotationUtils.buildTypeResolver(packageName, cu.getImports());
        Map<String, String> localTypes = collectLocalTypes(cu);

        Symbols symbols = new Symbols();
        if (isExportedPackageInfo(filePath, cu, packageName)) {
            symbols.exportedPackages.add(packageName);
        }
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            collectType(type, packageName, imports, localTypes, filePath, symbols);
        }
        collectCallSites(cu, packageName, imports, localTypes, filePath, symbols);

        List<CodeBlockSnapshot> codeBlocks = codeBlockVisitor.extract(cu, filePath, packageName);
        var leakResult = leakVisitor.analyze(cu, filePath);
        var subserviceCalls = new io.github.rohithv16.aem.analyser.core.parser.java.visitor.SubserviceCallExtractor()
                .extract(cu, packageName, filePath);
        int lineCount = cu.getRange().map(r -> r.end.line).orElse(0);

        return new JavaSourceParseResult(filePath.toAbsolutePath().normalize(), packageName,
                symbols.javaClasses, symbols.components, symbols.ocds, symbols.slingModels,
                symbols.pendingConsumptions, symbols.pendingServiceInjections, symbols.pendingAdaptTo,
                symbols.exportedPackages, codeBlocks, symbols.administrativeCalls,
                leakResult.leakFindings(), leakResult.traversalFindings(), subserviceCalls, lineCount);
    }

    /** Parses the file; {@code null} when it is unreadable and the caller is not strict. */
    private CompilationUnit parseUnit(Path filePath, boolean strict) {
        try {
            ParseResult<CompilationUnit> result = new JavaParser(PARSER_CONFIGURATION)
                    .parse(Files.newBufferedReader(filePath));
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                if (strict) {
                    throw new IllegalArgumentException("Java parse failed: " + result.getProblems());
                }
                return null;
            }
            return result.getResult().get();
        } catch (IOException e) {
            if (strict) {
                throw new UncheckedIOException(e);
            }
            return null;
        }
    }

    /** Simple name to fully qualified name for every type declared in the compilation unit. */
    private static Map<String, String> collectLocalTypes(CompilationUnit cu) {
        Map<String, String> localTypes = new LinkedHashMap<>();
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            type.getFullyQualifiedName()
                    .ifPresent(fqn -> localTypes.put(type.getNameAsString(), fqn));
        }
        return localTypes;
    }

    private static boolean isExportedPackageInfo(Path filePath, CompilationUnit cu, String packageName) {
        return filePath.getFileName().toString().equals("package-info.java")
                && !packageName.isEmpty()
                && cu.getPackageDeclaration().isPresent()
                && cu.getPackageDeclaration().get().getAnnotations().stream()
                .anyMatch(a -> AnnotationUtils.isAnnotation(a, "Export"));
    }

    /** Symbol extraction for one declared type: OCD, component, Sling model, or plain class. */
    private void collectType(TypeDeclaration<?> type, String packageName,
                             Map<String, String> imports, Map<String, String> localTypes,
                             Path filePath, Symbols symbols) {
        Optional<OSGiOcdNode> ocd = ocdVisitor.extract(type, filePath);
        if (ocd.isPresent()) {
            symbols.ocds.add(ocd.get());
            return;
        }
        Optional<OSGiComponentNode> component =
                componentVisitor.extract(type, packageName, imports, localTypes, filePath);
        if (component.isPresent()) {
            collectComponent(component.get(), type, packageName, imports, localTypes, filePath, symbols);
            return;
        }
        Optional<SlingModelNode> model =
                slingModelVisitor.extract(type, packageName, imports, localTypes, filePath);
        if (model.isPresent()) {
            collectSlingModel(model.get(), type, packageName, imports, localTypes, filePath, symbols);
            return;
        }
        collectPlainClass(type, packageName, imports, localTypes, filePath, symbols);
    }

    private void collectComponent(OSGiComponentNode componentNode, TypeDeclaration<?> type,
                                  String packageName, Map<String, String> imports,
                                  Map<String, String> localTypes, Path filePath, Symbols symbols) {
        symbols.components.add(componentNode);
        symbols.pendingConsumptions.addAll(
                extractConsumptions(componentNode.getFqn(), type, packageName, imports, localTypes, filePath));
        componentNode.getReferences().forEach((field, target) ->
                symbols.pendingServiceInjections.add(new PendingServiceInjection(
                        componentNode.getFqn(), target, false, filePath)));
    }

    private void collectSlingModel(SlingModelNode modelNode, TypeDeclaration<?> type,
                                   String packageName, Map<String, String> imports,
                                   Map<String, String> localTypes, Path filePath, Symbols symbols) {
        symbols.slingModels.add(modelNode);
        symbols.pendingConsumptions.addAll(
                extractConsumptions(modelNode.getFqn(), type, packageName, imports, localTypes, filePath));
        modelNode.getInjectedServices().forEach(service ->
                symbols.pendingServiceInjections.add(new PendingServiceInjection(
                        modelNode.getFqn(), service, true, filePath)));
    }

    private void collectPlainClass(TypeDeclaration<?> type, String packageName,
                                   Map<String, String> imports, Map<String, String> localTypes,
                                   Path filePath, Symbols symbols) {
        if (AnnotationUtils.findAnnotation(type.getAnnotations(), "Export").isPresent()) {
            symbols.exportedPackages.add(packageName);
        }
        JavaClassNode classNode = plainClassNode(type, packageName, imports, localTypes, filePath);
        symbols.javaClasses.add(classNode);
        symbols.pendingConsumptions.addAll(
                extractConsumptions(classNode.getFqn(), type, packageName, imports, localTypes, filePath));
        // A plain class can hold @Reference fields that the inheriting @Component actually consumes
        // (abstract servlet base classes). Emit them so the graph can attribute the injection to the
        // subclass instead of reporting the service as unused.
        componentVisitor.extractReferences(type, packageName, imports, localTypes)
                .forEach((field, target) -> symbols.pendingServiceInjections.add(
                        new PendingServiceInjection(classNode.getFqn(), target, false, filePath)));
    }

    /** adaptTo(Target.class) / ModelFactory.createModel scanning and administrative API calls. */
    private void collectCallSites(CompilationUnit cu, String packageName,
                                  Map<String, String> imports, Map<String, String> localTypes,
                                  Path filePath, Symbols symbols) {
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            collectCallSite(call, cu, packageName, imports, localTypes, filePath, symbols);
        }
    }

    private void collectCallSite(MethodCallExpr call, CompilationUnit cu, String packageName,
                                 Map<String, String> imports, Map<String, String> localTypes,
                                 Path filePath, Symbols symbols) {
        String name = call.getNameAsString();
        if (isAdministrativeCall(call)) {
            symbols.administrativeCalls.add(new AdministrativeCall(name, enclosingTypeFqn(call, cu),
                    filePath, MetatypeOcdVisitor.sourceLocation(call, filePath)));
            return;
        }
        if (!name.equals("adaptTo") && !name.equals("getModel") && !name.equals("createModel")) {
            return;
        }
        List<ClassExpr> classArgs = classArguments(call);
        if (classArgs.isEmpty() && name.equals("adaptTo") && !call.getArguments().isEmpty()) {
            return;
        }
        String sourceFqn = enclosingTypeFqn(call, cu);
        if (sourceFqn == null) {
            return;
        }
        for (ClassExpr classArg : classArgs) {
            String resolved = AnnotationUtils.resolveTypeName(
                    classArg.getType().asString(), packageName, imports, localTypes);
            symbols.pendingAdaptTo.add(new PendingAdaptTo(sourceFqn, resolved, filePath));
        }
    }

    private static List<ClassExpr> classArguments(MethodCallExpr call) {
        return call.getArguments().stream()
                .filter(ClassExpr.class::isInstance)
                .map(ClassExpr.class::cast)
                .toList();
    }

    /** Accumulator for the symbols collected while walking one compilation unit. */
    private static final class Symbols {
        private final List<JavaClassNode> javaClasses = new ArrayList<>();
        private final List<OSGiComponentNode> components = new ArrayList<>();
        private final List<OSGiOcdNode> ocds = new ArrayList<>();
        private final List<SlingModelNode> slingModels = new ArrayList<>();
        private final List<PendingConsumption> pendingConsumptions = new ArrayList<>();
        private final List<PendingServiceInjection> pendingServiceInjections = new ArrayList<>();
        private final List<PendingAdaptTo> pendingAdaptTo = new ArrayList<>();
        private final Set<String> exportedPackages = new LinkedHashSet<>();
        private final List<AdministrativeCall> administrativeCalls = new ArrayList<>();
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
