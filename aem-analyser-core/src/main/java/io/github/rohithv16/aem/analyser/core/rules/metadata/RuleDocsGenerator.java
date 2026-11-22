package io.github.rohithv16.aem.analyser.core.rules.metadata;

import io.github.rohithv16.aem.analyser.core.model.report.RuleId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the published rule reference: a filterable index at {@code issues/index.html} plus one
 * indexable page per rule at {@code issues/<anchor>/index.html}, each with what the rule detects,
 * why it matters on AEM, and a before/after code example.
 *
 * <p>The pages are generated rather than hand-written so a change to a rule's severity, description or
 * remediation cannot leave the site describing the old behaviour, and so the HTML dashboard can link
 * every finding to a target that is guaranteed to exist. A rule id is how developers arrive — from a
 * CI log, a SARIF alert or a Cloud Manager waiver — so every id owns a URL that can rank for itself
 * instead of thirty ids competing for one title.</p>
 */
public final class RuleDocsGenerator {

    /** Public site root, used for the URLs the dashboard and SARIF {@code helpUri} point at. */
    private static final String SITE_BASE = "https://rohithv16.github.io/aem-code-analyser";

    private static final List<String> FAMILIES =
            List.of("DEAD", "DUP", "QUAL", "PERF", "CLOUD", "SEC", "COMP");

    private static final Map<String, String> FAMILY_TITLES = Map.of(
            "DEAD", "Dead code",
            "DUP", "Duplication",
            "QUAL", "Quality and Cloud Manager parity",
            "PERF", "Performance",
            "CLOUD", "AEM as a Cloud Service",
            "SEC", "Security",
            "COMP", "Component compatibility");

    private static final Map<String, String> FAMILY_BLURBS = Map.of(
            "DEAD", "Code, configuration and content with no remaining consumer.",
            "DUP", "Two things claiming the same request, PID, resource type or code block.",
            "QUAL", "The checks Cloud Manager runs, plus the thread-safety rules it expects you to hold.",
            "PERF", "Resource lifecycles and repository query cost.",
            "CLOUD", "Patterns that break on AEM as a Cloud Service, including plaintext secrets.",
            "SEC", "Output escaping and request-driven redirects.",
            "COMP", "Deprecated Classic UI component definitions.");

    /** Meta descriptions longer than this are truncated on a word boundary; search results cut them. */
    private static final int DESCRIPTION_LIMIT = 158;

    /** A page title keeps its rule id and name; the symptom is added while it still fits. */
    private static final int TITLE_LIMIT = 118;

    /** Cloud Manager check ids quoted in a rule's short description, for example "(CQBP-75)". */
    private static final Pattern CLOUD_MANAGER_ID =
            Pattern.compile("\\(([A-Z]{2,}[A-Z0-9-]*[0-9]+|CQBP-ThreadSafety)\\)");

    private RuleDocsGenerator() {
    }

    private static String familyOf(String ruleId) {
        String[] parts = ruleId.split("-");
        return parts.length >= 2 ? parts[1] : "OTHER";
    }

    /** @return the anchor the index and older findings links use for this rule */
    public static String anchorFor(String ruleId) {
        return ruleId.toLowerCase(Locale.ROOT);
    }

    /** @return the public documentation URL for this rule: its own indexable page */
    public static String urlFor(String ruleId) {
        return SITE_BASE + "/issues/" + anchorFor(ruleId) + "/";
    }

    /**
     * Writes {@code <docs>/issues/index.html} — the filterable catalogue — and one indexable page per
     * rule at {@code <docs>/issues/<anchor>/index.html}.
     *
     * @param docsRoot the site root that contains {@code index.html} and {@code assets/}
     */
    public static void writeAll(Path docsRoot) throws IOException {
        Path issues = docsRoot.resolve("issues");
        Files.createDirectories(issues);

        List<RuleDescriptor> rules = RuleRegistry.all();
        Files.writeString(issues.resolve("index.html"), renderPage(rules), StandardCharsets.UTF_8);

        Set<String> anchors = new HashSet<>();
        for (RuleDescriptor rule : rules) {
            String anchor = anchorFor(rule.id());
            anchors.add(anchor);
            Path pageDir = issues.resolve(anchor);
            Files.createDirectories(pageDir);
            Files.writeString(pageDir.resolve("index.html"), renderRulePage(rule), StandardCharsets.UTF_8);
        }
        pruneStaleRulePages(issues, anchors);
    }

    /**
     * Removes a rule's page when the rule leaves the catalogue, so a retired id cannot keep ranking
     * with stale guidance. Only directories this generator wrote are touched.
     */
    private static void pruneStaleRulePages(Path issues, Set<String> anchors) throws IOException {
        List<Path> entries;
        try (var stream = Files.list(issues)) {
            entries = stream.toList();
        }
        for (Path entry : entries) {
            if (!Files.isDirectory(entry) || anchors.contains(entry.getFileName().toString())) {
                continue;
            }
            Path page = entry.resolve("index.html");
            if (!Files.isRegularFile(page)
                    || !Files.readString(page, StandardCharsets.UTF_8).startsWith("---\nlayout: default\n")) {
                continue;
            }
            try (var inner = Files.list(entry)) {
                for (Path child : inner.toList()) {
                    if (Files.isDirectory(child)) {
                        try (var deeper = Files.list(child)) {
                            for (Path file : deeper.toList()) {
                                Files.deleteIfExists(file);
                            }
                        }
                    }
                    Files.deleteIfExists(child);
                }
            }
            Files.deleteIfExists(entry);
        }
    }

    static String renderPage(List<RuleDescriptor> rules) {
        StringBuilder html = new StringBuilder(96_000);

        html.append("---\n");
        html.append("layout: default\n");
        html.append("nav: issues\n");
        html.append("title: Issues and fixes\n");
        html.append("description: Every AEM Code Analyser rule — what it detects, why it matters on ")
                .append("AEM as a Cloud Service, and how to fix it with a code example.\n");
        html.append("---\n\n");

        html.append("<div class=\"shell docs\">\n");

        html.append("  <div class=\"docs__main\">\n");

        html.append("    <div class=\"issues-head\">\n");
        html.append("      <h1>Issues and fixes</h1>\n");
        html.append("      <p class=\"lede\">Every rule in the catalogue: what it detects, why it ")
                .append("matters in an AEM project, and the fix with a before and after example. Every ")
                .append("finding in the HTML dashboard links straight to its entry here.</p>\n");
        html.append("      <div class=\"issue-tools\">\n");
        html.append("        <label class=\"visually-hidden\" for=\"issueSearch\">Filter issues</label>\n");
        html.append("        <input type=\"search\" id=\"issueSearch\" placeholder=\"Filter by id, name, ")
                .append("tag or symptom…\" autocomplete=\"off\">\n");
        html.append("        <button class=\"chip\" type=\"button\" data-family=\"all\" aria-pressed=\"true\">All ")
                .append(rules.size()).append("</button>\n");
        for (String family : FAMILIES) {
            long count = rules.stream().filter(rule -> family.equals(familyOf(rule.id()))).count();
            if (count == 0) {
                continue;
            }
            html.append("        <button class=\"chip\" type=\"button\" data-family=\"").append(family)
                    .append("\" aria-pressed=\"false\">").append(escape(shortFamily(family)))
                    .append(" ").append(count).append("</button>\n");
        }
        html.append("        <span class=\"issue-count\" id=\"issueCount\" aria-live=\"polite\">")
                .append(rules.size()).append(" of ").append(rules.size()).append(" issues</span>\n");
        html.append("      </div>\n");
        html.append("    </div>\n");

        for (String family : FAMILIES) {
            List<RuleDescriptor> inFamily = rules.stream()
                    .filter(rule -> family.equals(familyOf(rule.id())))
                    .toList();
            if (inFamily.isEmpty()) {
                continue;
            }
            html.append("\n    <section class=\"family\" id=\"family-")
                    .append(family.toLowerCase(Locale.ROOT)).append("\">\n");
            html.append("      <div class=\"family__head\">\n        <h2>")
                    .append(escape(FAMILY_TITLES.get(family))).append("</h2>\n")
                    .append("        <span>").append(inFamily.size())
                    .append(inFamily.size() == 1 ? " issue" : " issues").append("</span>\n")
                    .append("      </div>\n");
            html.append("      <p class=\"muted\">")
                    .append(escape(FAMILY_BLURBS.get(family))).append("</p>\n");
            for (RuleDescriptor rule : inFamily) {
                renderRuleCard(html, rule, family);
            }
            html.append("    </section>\n");
        }

        html.append("  </div>\n");
        // Sidebar: family jumps plus every rule id, so a finding's id is one click away.
        html.append("  <aside class=\"docs__aside\">\n");
        html.append("    <p class=\"aside__title\">Families</p>\n    <ul>\n");
        for (String family : FAMILIES) {
            long count = rules.stream().filter(rule -> family.equals(familyOf(rule.id()))).count();
            if (count == 0) {
                continue;
            }
            html.append("      <li><a href=\"#family-").append(family.toLowerCase(Locale.ROOT)).append("\">")
                    .append(escape(FAMILY_TITLES.get(family)))
                    .append(" <span class=\"muted\">").append(count).append("</span></a></li>\n");
        }
        html.append("    </ul>\n");

        html.append("    <p class=\"aside__title\">All rules</p>\n    <ul>\n");
        for (String family : FAMILIES) {
            rules.stream()
                    .filter(rule -> family.equals(familyOf(rule.id())))
                    .sorted(java.util.Comparator.comparing(RuleDescriptor::id))
                    .forEach(rule -> html.append("      <li><a href=\"#").append(anchorFor(rule.id()))
                            .append("\">").append(escape(rule.id())).append("</a></li>\n"));
        }
        html.append("    </ul>\n");
        html.append("  </aside>\n");

        html.append("</div>\n");
        return html.toString();
    }

    /**
     * Renders one rule as its own indexable page: a unique title, its own description, and the same
     * fix the index shows. Jekyll takes the canonical URL from the file path, so the page answers at
     * {@code /issues/<anchor>/} without a redirect.
     */
    static String renderRulePage(RuleDescriptor rule) {
        RuleFixCatalog.Fix fix = fixFor(rule);
        String family = familyOf(rule.id());
        String familyTitle = FAMILY_TITLES.getOrDefault(family, "AEM Code Analyser rules");
        StringBuilder html = new StringBuilder(26_000);

        html.append("---\n");
        html.append("layout: default\n");
        html.append("nav: issues\n");
        html.append("title: ").append(yaml(pageTitle(rule))).append('\n');
        html.append("description: ").append(yaml(pageDescription(rule))).append('\n');
        html.append("rule_id: ").append(yaml(rule.id())).append('\n');
        html.append("rule_family: ").append(yaml(family)).append('\n');
        html.append("rule_family_title: ").append(yaml(familyTitle)).append('\n');
        html.append("rule_severity: ").append(yaml(rule.defaultSeverity().name())).append('\n');
        html.append("rule_tags: ").append(yaml(String.join(", ", rule.tags()))).append('\n');
        html.append("---\n\n");

        html.append("<div class=\"shell docs\">\n");
        html.append("  <div class=\"docs__main\">\n");
        html.append("    <p class=\"muted\"><a href=\"../\">← All ").append(RuleRegistry.all().size())
                .append(" rules</a></p>\n");
        html.append("    <div class=\"issues-head\">\n");
        html.append("      <h1><span class=\"rule-card__id\">").append(escape(rule.id())).append("</span> ")
                .append(escape(rule.name())).append("</h1>\n");
        html.append("      <p class=\"lede\">").append(escape(normalise(rule.shortDescription())))
                .append("</p>\n");
        html.append("      <p class=\"muted\">").append(severityPill(rule)).append(" · ")
                .append(rule.effortMinutes()).append(" min fix · ").append(escape(typeLabel(rule.type())))
                .append(" · <a href=\"../#family-").append(family.toLowerCase(Locale.ROOT)).append("\">")
                .append(escape(familyTitle)).append("</a></p>\n");
        html.append("    </div>\n\n");

        html.append("    <article class=\"rule-card rule-card--page\" id=\"").append(anchorFor(rule.id()))
                .append("\" data-family=\"").append(family).append("\">\n");
        renderRuleBody(html, rule, fix, "h2");
        html.append("    </article>\n");

        html.append("    <p class=\"muted\">Every rule in the catalogue, with what it detects and how to ")
                .append("fix it: <a href=\"../\">all ").append(RuleRegistry.all().size())
                .append(" rules</a>. Suppress a single line with <code>")
                .append(escape(suppressionComment(rule.id()))).append("</code>.</p>\n");
        html.append("  </div>\n");
        html.append("</div>\n");
        return html.toString();
    }

    /** The unique {@code <title>} for a rule page: its id, its name, then the symptom while it fits. */
    static String pageTitle(RuleDescriptor rule) {
        String head = rule.id() + " " + rule.name();
        String symptom = normalise(rule.shortDescription());
        if (symptom.isEmpty()) {
            return head;
        }
        return shorten(head + " — " + symptom, TITLE_LIMIT);
    }

    /** The meta description for a rule page: the rule's own blurb, led by its id and severity. */
    static String pageDescription(RuleDescriptor rule) {
        String blurb = normalise(rule.shortDescription());
        String prefix = rule.id() + " (" + rule.defaultSeverity() + "): ";
        if (blurb.isEmpty()) {
            blurb = normalise(rule.remediation());
        }
        return shorten(prefix + blurb, DESCRIPTION_LIMIT);
    }

    /** Cuts on a word boundary and marks the cut, so a truncated description never ends mid-word. */
    static String shorten(String value, int limit) {
        String text = value.strip();
        if (text.length() <= limit) {
            return text;
        }
        int cut = text.lastIndexOf(' ', limit - 1);
        if (cut < limit / 2) {
            cut = limit - 1;
        }
        return text.substring(0, cut).stripTrailing().replaceAll("[,;:]$", "") + " …";
    }

    private static String shortFamily(String family) {
        return switch (family) {
            case "DEAD" -> "Dead code";
            case "DUP" -> "Duplication";
            case "QUAL" -> "Quality";
            case "PERF" -> "Performance";
            case "CLOUD" -> "Cloud Service";
            case "SEC" -> "Security";
            case "COMP" -> "Components";
            default -> family;
        };
    }

    private static void renderRuleCard(StringBuilder html, RuleDescriptor rule, String family) {
        String cloudManagerId = cloudManagerId(rule.shortDescription());

        html.append("\n      <article class=\"rule-card\" id=\"").append(anchorFor(rule.id())).append("\"")
                .append(" data-family=\"").append(family).append("\"")
                .append(" data-search=\"").append(escape(searchText(rule, fixFor(rule), cloudManagerId)))
                .append("\">\n");

        html.append("        <header class=\"rule-card__head\">\n");
        html.append("          <h3 class=\"rule-card__title\"><a class=\"rule-card__permalink\" href=\"")
                .append(anchorFor(rule.id())).append("/\" aria-label=\"Open the ")
                .append(escape(rule.id())).append(" reference page\"><span class=\"rule-card__id\">")
                .append(escape(rule.id())).append("</span></a><a class=\"rule-card__anchor\" href=\"#")
                .append(anchorFor(rule.id())).append("\" aria-label=\"Link to ").append(escape(rule.id()))
                .append("\">#</a><span class=\"rule-card__name\">").append(escape(rule.name()))
                .append("</span></h3>\n");
        html.append("          ").append(severityPill(rule)).append('\n');
        html.append("          <span class=\"rule-card__meta\">\n");
        html.append("            <span>").append(rule.effortMinutes()).append(" min fix</span>\n");
        if (cloudManagerId != null) {
            html.append("            <span>Cloud Manager ").append(escape(cloudManagerId)).append("</span>\n");
        }
        if (rule.cwe() != null) {
            html.append("            <span>").append(escape(rule.cwe())).append("</span>\n");
        }
        if ("AEM-QUAL-006".equals(rule.id())) {
            html.append("            <span>retired id</span>\n");
        }
        html.append("            <span>").append(typeLabel(rule.type())).append("</span>\n");
        html.append("          </span>\n");
        html.append("        </header>\n");

        renderRuleBody(html, rule, fixFor(rule), "h4");

        html.append("      </article>\n");
    }

    /**
     * The explanation every surface shares: summary, why it matters, the fix and the before/after
     * examples. The index card wraps it in a {@code <header>}; the rule page puts that in its own
     * {@code <h1>} so the page has exactly one top-level heading.
     */
    private static void renderRuleBody(StringBuilder html, RuleDescriptor rule, RuleFixCatalog.Fix fix,
                                       String headingTag) {
        html.append("        <p class=\"rule-card__summary\">").append(escape(normalise(rule.shortDescription())))
                .append("</p>\n");

        if (fix.why() != null && !fix.why().isBlank()) {
            html.append("        <").append(headingTag).append(">Why it matters</").append(headingTag)
                    .append(">\n        <p>")
                    .append(escape(normalise(fix.why()))).append("</p>\n");
        }

        html.append("        <").append(headingTag).append(">How to fix it</").append(headingTag)
                .append(">\n        <p>")
                .append(escape(fixText(rule, fix))).append("</p>\n");

        if (fix.hasBadExample() || fix.hasGoodExample()) {
            html.append("        <div class=\"examples\">\n");
            if (fix.hasBadExample()) {
                html.append("          <div class=\"code code--bad\">\n")
                        .append("            <div class=\"code__bar\"><span class=\"code__label\">")
                        .append(escape(fix.badLang() == null ? "before" : fix.badLang() + " · before"))
                        .append("</span></div>\n")
                        .append("            <pre><code>").append(escape(fix.badCode())).append("</code></pre>\n")
                        .append("          </div>\n");
            }
            if (fix.hasGoodExample()) {
                html.append("          <div class=\"code code--good\">\n")
                        .append("            <div class=\"code__bar\"><span class=\"code__label\">")
                        .append(escape(fix.goodLang() == null ? "after" : fix.goodLang() + " · after"))
                        .append("</span></div>\n")
                        .append("            <pre><code>").append(escape(fix.goodCode())).append("</code></pre>\n")
                        .append("          </div>\n");
            }
            html.append("        </div>\n");
        }

        html.append("        <p class=\"suppress\">Suppress one line: <code>")
                .append(escape(suppressionComment(rule.id()))).append("</code>");
        if (!rule.tags().isEmpty()) {
            html.append(" · tags ").append(escape(String.join(", ", rule.tags())));
        }
        html.append(" · <code>--explain ").append(escape(rule.id())).append("</code></p>\n");
    }

    private static String severityPill(RuleDescriptor rule) {
        String severityClass = switch (rule.defaultSeverity()) {
            case ERROR -> "pill--error";
            case WARNING -> "pill--warning";
            case INFO -> "pill--info";
        };
        return "<span class=\"pill " + severityClass + "\">" + rule.defaultSeverity() + "</span>";
    }

    private static RuleFixCatalog.Fix fixFor(RuleDescriptor rule) {
        return RuleFixCatalog.forRule(RuleId.valueOf(rule.id().replace('-', '_')));
    }

    /** Issue types read as metadata chips, so they need a label rather than an enum constant. */
    private static String typeLabel(RuleType type) {
        return switch (type) {
            case BUG -> "Bug";
            case VULNERABILITY -> "Vulnerability";
            case CODE_SMELL -> "Code smell";
            case SECURITY_HOTSPOT -> "Security hotspot";
        };
    }

    /** Curated steps win over the one-line registry remediation; they say the same thing in detail. */
    private static String fixText(RuleDescriptor rule, RuleFixCatalog.Fix fix) {
        String text = fix.steps() == null || fix.steps().isBlank() ? rule.remediation() : fix.steps();
        return normalise(text);
    }

    /** The suppression comment that works for this rule's file type. */
    private static String suppressionComment(String ruleId) {
        boolean markup = ruleId.startsWith("AEM-SEC-001")
                || ruleId.startsWith("AEM-COMP-001")
                || ruleId.startsWith("AEM-DEAD-004")
                || ruleId.startsWith("AEM-DEAD-005")
                || ruleId.startsWith("AEM-CLOUD-002");
        return markup
                ? "<!-- aem-analyser-disable-next-line " + ruleId + " -->"
                : "// aem-analyser-disable-next-line " + ruleId;
    }

    private static String cloudManagerId(String description) {
        if (description == null) {
            return null;
        }
        Matcher matcher = CLOUD_MANAGER_ID.matcher(description);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String searchText(RuleDescriptor rule, RuleFixCatalog.Fix fix, String cloudManagerId) {
        StringBuilder text = new StringBuilder();
        text.append(rule.id()).append(' ').append(rule.name()).append(' ')
                .append(normalise(rule.shortDescription())).append(' ')
                .append(String.join(" ", rule.tags())).append(' ')
                .append(rule.defaultSeverity()).append(' ')
                .append(normalise(rule.remediation())).append(' ');
        if (cloudManagerId != null) {
            text.append(cloudManagerId).append(' ');
        }
        if (fix.why() != null) {
            text.append(normalise(fix.why())).append(' ');
        }
        if (fix.steps() != null) {
            text.append(normalise(fix.steps()));
        }
        return text.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /**
     * Turns Javadoc inline tags into plain prose. Rule text is written for {@code --explain} and
     * {@code @ObjectClassDefinition} Javadoc, where {@code {@code x}} renders as code; the published
     * page must not show the raw tag.
     */
    static String normalise(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("\\{@code\\s+([^}]*)}", "$1")
                .replaceAll("\\{@code\\s*}", "")
                .replaceAll("\\{@link\\s+([^}]*)}", "$1")
                .replaceAll("\\{@literal\\s+([^}]*)}", "$1")
                .replace("<p>", "")
                .replace("</p>", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** HTML-escapes text so the generated page stays valid whatever a description contains. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * A YAML double-quoted scalar. Front matter is YAML, so a rule name containing a colon, a quote
     * or a leading punctuation character must be quoted rather than pasted in.
     */
    static String yaml(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append(' ');
                case '\r' -> out.append("");
                case '\t' -> out.append(' ');
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    public static void main(String[] args) throws IOException {
        Path docsRoot = args.length > 0 ? Path.of(args[0]) : Path.of("docs");
        writeAll(docsRoot);
        List<RuleDescriptor> rules = RuleRegistry.all();
        List<String> anchors = new ArrayList<>();
        rules.forEach(rule -> anchors.add(anchorFor(rule.id())));
        System.out.println("Wrote the issues reference (" + rules.size() + " rules, "
                + (anchors.size() + 1) + " pages) to " + docsRoot.resolve("issues"));
    }
}
