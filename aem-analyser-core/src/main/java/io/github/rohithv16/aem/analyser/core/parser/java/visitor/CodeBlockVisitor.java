package io.github.rohithv16.aem.analyser.core.parser.java.visitor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import io.github.rohithv16.aem.analyser.core.parser.java.CodeBlockSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extracts non-trivial method blocks from Java compilation units for duplicate code detection.
 */
public final class CodeBlockVisitor {

    private static final int MIN_STATEMENTS = 4;
    private static final int MIN_LINES = 8;

    private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern NUMBER_LITERAL_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?(?:[fFdDlL])?\\b");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    public List<CodeBlockSnapshot> extract(CompilationUnit cu, Path filePath, String packageName) {
        List<CodeBlockSnapshot> blocks = new ArrayList<>();
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cid -> {
            if (cid.isInterface()) {
                return;
            }
            String className = cid.getNameAsString();
            String fqn = packageName.isEmpty() ? className : packageName + "." + className;

            for (MethodDeclaration md : cid.getMethods()) {
                if (md.getBody().isEmpty() || isTestMethod(md)) {
                    continue;
                }
                BlockStmt body = md.getBody().get();
                int statementCount = body.getStatements().size();
                if (statementCount < MIN_STATEMENTS) {
                    continue;
                }

                if (body.getRange().isEmpty()) {
                    continue;
                }

                int startLine = body.getRange().get().begin.line;
                int endLine = body.getRange().get().end.line;
                int lineCount = endLine - startLine + 1;
                if (lineCount < MIN_LINES) {
                    continue;
                }

                String rawBody = body.toString();
                String normalized = normalize(rawBody);
                if (normalized.length() < 50) {
                    continue;
                }

                String hash = hash(normalized);
                String preview = extractPreview(rawBody);

                blocks.add(new CodeBlockSnapshot(
                        filePath,
                        fqn,
                        md.getNameAsString(),
                        startLine,
                        endLine,
                        lineCount,
                        statementCount,
                        hash,
                        preview
                ));
            }
        });
        return blocks;
    }

    private boolean isTestMethod(MethodDeclaration md) {
        return md.getAnnotations().stream().anyMatch(a -> {
            String name = a.getNameAsString();
            return name.equals("Test") || name.equals("ParameterizedTest") ||
                    name.equals("BeforeEach") || name.equals("AfterEach") ||
                    name.equals("BeforeAll") || name.equals("AfterAll");
        });
    }

    private String normalize(String code) {
        // Strip comments
        String stripped = code.replaceAll("//.*|/\\*(?:.|[\\n\\r])*?\\*/", "");
        // Normalize string and numeric literals
        String noStrings = STRING_LITERAL_PATTERN.matcher(stripped).replaceAll("\"_STR_\"");
        String noNums = NUMBER_LITERAL_PATTERN.matcher(noStrings).replaceAll("_NUM_");
        // Normalize whitespace
        return WHITESPACE_PATTERN.matcher(noNums).replaceAll(" ").trim();
    }

    private String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }

    private String extractPreview(String body) {
        String[] lines = body.lines().limit(3).toArray(String[]::new);
        return String.join(" ", lines).replaceAll("\\s+", " ").trim();
    }
}
