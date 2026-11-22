package io.github.rohithv16.aem.analyser.core.rules.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rohithv16.aem.analyser.core.discovery.FileInventory;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule AEM-DEAD-006 — a translation key with no entry in the baseline dictionary.
 *
 * <p>AEM renders the raw key when a dictionary has no entry for it, so a missing translation is
 * invisible to the build, the bundle and the package manager and only appears as
 * "checkout.title" in front of a customer. This rule compares the keys used by templates
 * ({@code ${'key' @ i18n}}) and models ({@code i18n.get("key")}) with the keys declared by the
 * project's baseline {@code en} dictionary under an {@code i18n} folder.</p>
 *
 * <p>Precision guards:</p>
 * <ul>
 *   <li>a computed key ({@code ${model.key @ i18n}}, {@code i18n.get(KEY)}) is never judged;</li>
 *   <li>a baseline that exists only as XML, or a JSON baseline that does not parse, makes the check
 *       "cannot tell" and the rule stays silent instead of reporting every key;</li>
 *   <li>when there is no baseline dictionary at all the rule emits one INFO naming the locales that
 *       do exist, never one warning per key — without an English dictionary there is nothing to
 *       compare against.</li>
 * </ul>
 */
public final class AemDead006Rule implements RuleEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern HTL_I18N = Pattern.compile(
            "\\$\\{\\s*(['\"])(.*?)\\1\\s*@([^}]*)}", Pattern.DOTALL);

    private static final Pattern JAVA_I18N_GET = Pattern.compile(
            "\\b[iI]18n\\s*\\.\\s*get\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*[,)]");

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    @Override
    public String getRuleId() {
        return RuleId.AEM_DEAD_006.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Path> files = files(context);
        if (files.isEmpty()) {
            return List.of();
        }
        List<Path> dictionaries = files.stream().filter(AemDead006Rule::isDictionary).toList();
        List<Path> baselineFiles = dictionaries.stream()
                .filter(file -> isBaseline(localeOf(file)))
                .toList();

        List<KeyUsage> usages = new ArrayList<>();
        for (Path file : files) {
            if (isTemplate(file)) {
                usages.addAll(htlUsages(file));
            } else if (isJava(file)) {
                usages.addAll(javaUsages(file));
            }
        }

        if (baselineFiles.isEmpty()) {
            return missingBaseline(context, dictionaries, usages);
        }

        Set<String> baselineKeys = new LinkedHashSet<>();
        for (Path baseline : baselineFiles) {
            Set<String> keys = readJsonKeys(baseline);
            if (keys == null) {
                // An XML or malformed baseline cannot be read as JSON: every key would look
                // missing, which is exactly the false positive this rule must not ship.
                return List.of();
            }
            baselineKeys.addAll(keys);
        }

        List<Violation> violations = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();
        for (KeyUsage usage : usages) {
            if (baselineKeys.contains(usage.key())) {
                continue;
            }
            if (!reported.add(usage.file() + ":" + usage.line() + ":" + usage.key())) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_DEAD_006.id(),
                    RuleId.AEM_DEAD_006.defaultLevel(),
                    "MISSING_TRANSLATION_KEY",
                    "Translation key '" + usage.key() + "' has no entry in the baseline (en)"
                            + " dictionary; AEM renders the raw key on the page.",
                    context.relativePath(usage.file()),
                    new TextRegion(usage.line(), usage.column(), usage.line(),
                            usage.column() + usage.key().length()),
                    usage.key(),
                    "Add the key to " + relativeName(context, baselineFiles.get(0))
                            + " (and to every other locale), or correct the key at the use site.",
                    List.of()));
        }
        return violations;
    }

    private List<Violation> missingBaseline(RuleContext context, List<Path> dictionaries,
                                            List<KeyUsage> usages) {
        if (dictionaries.isEmpty() && usages.isEmpty()) {
            return List.of();
        }
        Path location = dictionaries.isEmpty() ? usages.get(0).file() : dictionaries.get(0);
        String locales = dictionaries.stream()
                .map(AemDead006Rule::localeOf)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .toString();
        String detail = dictionaries.isEmpty()
                ? "no i18n dictionary exists in the project"
                : "only non-baseline dictionaries exist (" + locales + ")";
        int line = dictionaries.isEmpty() ? usages.get(0).line() : 1;
        int column = dictionaries.isEmpty() ? usages.get(0).column() : 1;
        return List.of(new Violation(
                RuleId.AEM_DEAD_006.id(),
                Severity.INFO,
                "MISSING_BASELINE_DICTIONARY",
                "No baseline (en) i18n dictionary: " + detail + ", so translation keys used by"
                        + " templates and models cannot be checked.",
                context.relativePath(location),
                new TextRegion(line, column, line, column),
                null,
                "Add en.json under the project's i18n content folder and declare every used key.",
                List.of()));
    }

    private List<Path> files(RuleContext context) {
        FileInventory inventory = context.inventory();
        if (inventory.isEmpty() && context.projectRoot() != null) {
            inventory = FileInventory.scan(context.projectRoot());
        }
        return inventory.files();
    }

    private List<KeyUsage> htlUsages(Path file) {
        String content = read(file);
        if (content == null) {
            return List.of();
        }
        List<KeyUsage> usages = new ArrayList<>();
        Matcher matcher = HTL_I18N.matcher(content);
        while (matcher.find()) {
            if (!usesI18nOption(matcher.group(3))) {
                continue;
            }
            String key = matcher.group(2).strip();
            if (key.isEmpty()) {
                continue;
            }
            usages.add(new KeyUsage(file, key, lineOf(content, matcher.start(2)),
                    columnOf(content, matcher.start(2))));
        }
        return usages;
    }

    private List<KeyUsage> javaUsages(Path file) {
        String content = read(file);
        if (content == null) {
            return List.of();
        }
        List<KeyUsage> usages = new ArrayList<>();
        Matcher matcher = JAVA_I18N_GET.matcher(content);
        while (matcher.find()) {
            if (isInsideComment(content, matcher.start())) {
                continue;
            }
            String key = matcher.group(1);
            if (key.isBlank()) {
                continue;
            }
            usages.add(new KeyUsage(file, key, lineOf(content, matcher.start(1)),
                    columnOf(content, matcher.start(1))));
        }
        return usages;
    }

    private static boolean usesI18nOption(String options) {
        for (String option : options.split(",")) {
            String token = option.strip();
            if (token.equals("i18n") || token.startsWith("i18n ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInsideComment(String content, int offset) {
        int lineStart = content.lastIndexOf('\n', offset - 1) + 1;
        String prefix = content.substring(lineStart, offset).strip();
        return prefix.startsWith("//") || prefix.startsWith("*") || prefix.startsWith("/*");
    }

    private static boolean isTemplate(Path file) {
        return file.getFileName().toString().endsWith(".html");
    }

    private static boolean isJava(Path file) {
        return file.getFileName().toString().endsWith(".java");
    }

    private static boolean isDictionary(Path file) {
        Path parent = file.getParent();
        if (parent == null || parent.getFileName() == null
                || !"i18n".equals(parent.getFileName().toString())) {
            return false;
        }
        String name = file.getFileName().toString();
        if (name.startsWith(".")) {
            // .content.xml and other dot-files are vault metadata, not locale dictionaries.
            return false;
        }
        return name.endsWith(".json") || name.endsWith(".xml");
    }

    private static boolean isBaseline(String locale) {
        String normalized = locale.toLowerCase(Locale.ROOT);
        return normalized.equals("en") || normalized.startsWith("en_")
                || normalized.startsWith("en-");
    }

    private static String localeOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** @return the JSON object's keys, or {@code null} when the file is not a readable JSON object */
    private static Set<String> readJsonKeys(Path file) {
        if (!file.getFileName().toString().endsWith(".json")) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            if (root == null || !root.isObject()) {
                return null;
            }
            Set<String> keys = new LinkedHashSet<>();
            root.fieldNames().forEachRemaining(keys::add);
            return keys;
        } catch (IOException e) {
            return null;
        }
    }

    private static String read(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_SIZE_BYTES) {
                return null;
            }
            return Files.readString(file);
        } catch (IOException e) {
            return null;
        }
    }

    private static String relativeName(RuleContext context, Path file) {
        String relative = context.relativePath(file);
        return relative.substring(relative.lastIndexOf('/') + 1);
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static int columnOf(String content, int offset) {
        int newline = content.lastIndexOf('\n', offset - 1);
        return offset - newline;
    }

    private record KeyUsage(Path file, String key, int line, int column) {
    }
}
