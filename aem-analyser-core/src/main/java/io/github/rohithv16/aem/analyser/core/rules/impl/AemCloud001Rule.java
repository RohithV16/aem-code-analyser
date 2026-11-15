package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.SourceLocation;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.ConfigPropertyNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rule AEM-CLOUD-001: Hardcoded Cloud Secret.
 * Flags plaintext credentials and secrets committed in OSGi configuration files
 * instead of Adobe Cloud Manager environment variables ($[secret:VAR_NAME]).
 */
public final class AemCloud001Rule implements RuleEvaluator {

    private static final Pattern SECRET_KEY_PATTERN = Pattern.compile(
            "(?i).*(password|secret|apikey|api[._-]?key|private[._-]?key|authToken|auth[._-]?token|clientSecret|client[._-]?secret|accessToken|access[._-]?token|credential).*"
    );

    private static final Pattern NON_SECRET_SUFFIX_PATTERN = Pattern.compile(
            "(?i).*(timeout|expiry|ttl|length|pattern|format|enabled|required|algorithm|generator|validator|provider|class)$"
    );

    @Override
    public String getRuleId() {
        return RuleId.AEM_CLOUD_001.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        if (context.configs() == null || context.configs().isEmpty()) {
            return violations;
        }

        for (OSGiConfigNode config : context.configs()) {
            for (ConfigPropertyNode prop : config.getProperties().values()) {
                String key = prop.getKey();
                if (!isSecretKey(key)) {
                    continue;
                }

                if (prop.isEnvPlaceholder()) {
                    continue;
                }

                String val = prop.getRawValue();
                if (isBenignValue(val)) {
                    continue;
                }

                SourceLocation loc = prop.getSourceLocation();
                TextRegion region = loc != null && loc.line() > 0
                        ? new TextRegion(loc.line(), loc.column(), loc.line(), loc.column() + key.length())
                        : TextRegion.forWholeFile();

                violations.add(new Violation(
                        RuleId.AEM_CLOUD_001.id(),
                        RuleId.AEM_CLOUD_001.defaultLevel(),
                        "HARDCODED_CLOUD_SECRET",
                        "OSGi configuration property '" + prop.getKey() + "' in '" + config.getPid()
                                + "' contains a plaintext hardcoded secret.",
                        relativePath(context, config.getFilePath()),
                        region,
                        null,
                        "Externalize secret via Adobe Cloud Manager secret variable: '$[secret:"
                                + suggestEnvName(config.getPid(), prop.getKey()) + "]'.",
                        List.of()
                ));
            }
        }

        return violations;
    }

    private boolean isSecretKey(String key) {
        if (key == null) {
            return false;
        }
        return SECRET_KEY_PATTERN.matcher(key).matches()
                && !NON_SECRET_SUFFIX_PATTERN.matcher(key).matches();
    }

    private boolean isBenignValue(String val) {
        if (val == null) {
            return true;
        }
        String trimmed = val.trim();
        if (trimmed.isEmpty() || "\"\"".equals(trimmed) || "''".equals(trimmed)) {
            return true;
        }
        if (trimmed.contains("$[secret:") || trimmed.contains("$[env:") || trimmed.contains("$[property:")) {
            return true;
        }
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if (trimmed.matches("^-?\\d+$")) {
            return true;
        }
        return false;
    }

    private String suggestEnvName(String pid, String key) {
        String cleanPid = pid.substring(pid.lastIndexOf('.') + 1);
        return (cleanPid + "_" + key).replaceAll("[^A-Za-z0-9]", "_").toUpperCase(Locale.ROOT);
    }

    private String relativePath(RuleContext context, java.nio.file.Path path) {
        if (path == null) {
            return "";
        }
        return context.projectRoot().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
