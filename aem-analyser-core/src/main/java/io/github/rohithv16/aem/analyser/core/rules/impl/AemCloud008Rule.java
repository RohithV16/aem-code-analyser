package io.github.rohithv16.aem.analyser.core.rules.impl;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import io.github.rohithv16.aem.analyser.core.model.cloud.ImmutablePathWrite;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.java.visitor.ImmutablePathWriteExtractor;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AEM-CLOUD-008: runtime writes into {@code /apps} or {@code /libs}.
 *
 * <p>Both areas are immutable in AEM as a Cloud Service: the repository structure is decided at
 * build time by the packages a deployment installs, and "any attempt to change an immutable area at
 * runtime fails". Code that wrote {@code /apps/<project>/config} for years on 6.5 therefore fails
 * only once it runs on Cloud, with a {@code AccessDeniedException} or a silently dropped property —
 * nothing in javac, the SDK or Cloud Manager sees it.
 *
 * <p>The rule reports a write, never a read: every component reads its own configuration from
 * {@code /apps}. The extractor proves the mutation on the same statement (chained
 * {@code setProperty}/{@code put}/{@code adaptTo(ModifiableValueMap.class)}) or through the variable
 * the node was stored in. Mutable roots such as {@code /var}, {@code /content} and
 * {@code /conf} never match.
 */
public final class AemCloud008Rule implements RuleEvaluator {

    private static final ParserConfiguration PARSER_CONFIGURATION = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    private final ImmutablePathWriteExtractor extractor = new ImmutablePathWriteExtractor();

    @Override
    public String getRuleId() {
        return RuleId.AEM_CLOUD_008.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (Path file : context.fileInventory().javaFiles()) {
            if (!candidate(file)) {
                continue;
            }
            CompilationUnit unit = parse(file);
            if (unit == null) {
                continue;
            }
            for (ImmutablePathWrite write : extractor.extract(unit, file)) {
                violations.add(violation(context, write));
            }
        }
        return violations;
    }

    /**
     * Cheap pre-filter: without a string literal starting with {@code /apps} or {@code /libs} - or a
     * root-relative {@code addNode("apps")} - the compilation unit cannot produce a finding, and
     * parsing every file in the reactor would cost more than the rule is worth.
     */
    private boolean candidate(Path file) {
        try {
            String source = Files.readString(file);
            return source.contains("\"/apps") || source.contains("\"/libs")
                    || source.contains("getRootNode()");
        } catch (IOException unreadable) {
            return false;
        }
    }

    private CompilationUnit parse(Path file) {
        try {
            ParseResult<CompilationUnit> result = new JavaParser(PARSER_CONFIGURATION)
                    .parse(file);
            return result.getResult().orElse(null);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private Violation violation(RuleContext context, ImmutablePathWrite write) {
        boolean apps = write.path().startsWith("/apps");
        return new Violation(
                RuleId.AEM_CLOUD_008.id(),
                RuleId.AEM_CLOUD_008.defaultLevel(),
                "IMMUTABLE_PATH_WRITE",
                "Runtime write to immutable path '" + write.path() + "' via '" + write.operation()
                        + "'. AEM as a Cloud Service rejects changes to "
                        + (apps ? "/apps" : "/libs") + " at runtime.",
                context.relativePath(write.filePath()),
                new TextRegion(write.line(), write.column(), write.line(), write.column() + 10),
                null,
                "Ship the node as content in a content package instead of writing it at runtime, or "
                        + "keep runtime state under a mutable path such as /var or /conf.",
                List.of());
    }
}
