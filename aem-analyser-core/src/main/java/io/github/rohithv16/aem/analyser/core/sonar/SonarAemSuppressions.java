package io.github.rohithv16.aem.analyser.core.sonar;

import io.github.rohithv16.aem.analyser.core.model.report.Violation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * AEM-aware suppressions for embedded SonarJava findings that are correct Java style rules but wrong
 * for AEM framework contracts.
 *
 * <p>Each suppression is intentionally narrow (rule + source pattern) so a real defect in the same
 * rule family is still reported.
 */
public final class SonarAemSuppressions {

    private SonarAemSuppressions() {
    }

    public static boolean isSuppressed(Path projectRoot, Violation violation) {
        if (violation == null || violation.filePath() == null || violation.region() == null) {
            return false;
        }
        String ruleId = violation.ruleId();
        if (!"java:S100".equals(ruleId) && !"java:S1186".equals(ruleId) && !"java:S1192".equals(ruleId)) {
            return false;
        }
        Path file = resolve(projectRoot, violation.filePath());
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return false;
        }
        int index = violation.region().startLine() - 1;
        String line = index >= 0 && index < lines.size() ? lines.get(index) : "";
        String source = String.join("\n", lines);

        return switch (ruleId) {
            // OSGi Metatype reserved method name used by the Felix Web Console factory hint.
            case "java:S100" -> line.contains("webconsole_configurationFactory_nameHint");
            // Servlet Filter lifecycle no-ops are required by the servlet contract.
            case "java:S1186" -> isServletLifecycleNoOp(line, source);
            // JCR node names/types are not constant candidates, even when they repeat.
            case "java:S1192" -> isJcrNodeLiteral(line);
            default -> false;
        };
    }

    private static boolean isServletLifecycleNoOp(String line, String source) {
        String trimmed = line.strip();
        boolean lifecycleMethod = trimmed.contains("void init(") || trimmed.contains("void destroy(")
                || trimmed.contains("public void init") || trimmed.contains("public void destroy");
        if (!lifecycleMethod) {
            return false;
        }
        String lower = source.toLowerCase(Locale.ROOT);
        return lower.contains("implements filter") || lower.contains("extends genericservlet")
                || lower.contains("javax.servlet.filter") || lower.contains("jakarta.servlet.filter");
    }

    private static boolean isJcrNodeLiteral(String line) {
        return line.contains("addNode(") || line.contains("getNode(") || line.contains("hasNode(")
                || line.contains("\"nt:") || line.contains("\"cq:") || line.contains("\"rep:")
                || line.contains("\"sling:");
    }

    private static Path resolve(Path projectRoot, String filePath) {
        Path path = Path.of(filePath);
        if (path.isAbsolute()) {
            return path;
        }
        return projectRoot == null ? path : projectRoot.resolve(path);
    }
}
