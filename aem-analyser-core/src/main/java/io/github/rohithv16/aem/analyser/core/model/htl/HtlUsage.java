package io.github.rohithv16.aem.analyser.core.model.htl;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Represents a data-sly-use reference in an HTL template.
 *
 * <p>A use-API target can be three very different things: a Java type
 * ({@code com.acme.models.HeroModel}), a non-Java use-API resource
 * ({@code data.json}, {@code nav.js}, {@code templates.html}), or a dynamic
 * expression that cannot be resolved statically ({@code ${component.modelClass}},
 * {@code ${'com.acme.models.HeroModel' @ title=...}} after option extraction).
 * {@link #kind()} classifies the target once so that rules (notably
 * AEM-DEAD-004) only inspect Java types.</p>
 */
public record HtlUsage(
        Path filePath,
        int line,
        int column,
        String identifier,
        String target
) {

    /** What a data-sly-use target actually refers to. */
    public enum TargetKind {
        /** A Java class / Sling Model name that should exist in the project. */
        JAVA_CLASS,
        /** A non-Java use-API resource: another template, a JS file, a JSON data file. */
        RESOURCE,
        /** A dynamic or malformed expression that cannot be resolved statically. */
        UNRESOLVED
    }

    /** A Java identifier, optionally package-qualified; no $, no expression syntax. */
    private static final Pattern JAVA_NAME_PATTERN = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"
    );

    /** File extensions that identify a use-API resource rather than a Java type. */
    private static final Set<String> RESOURCE_EXTENSIONS = Set.of(
            "json", "xml", "csv", "yaml", "yml", "txt", "properties", "html", "htm",
            "js", "mjs", "cjs", "jsp", "ftl", "vm", "css", "scss", "svg", "xhtml"
    );

    public TargetKind kind() {
        if (target == null || target.isBlank()) {
            return TargetKind.UNRESOLVED;
        }
        String clean = target.strip();
        if (clean.isEmpty()) {
            return TargetKind.UNRESOLVED;
        }
        // A path (templates.html, /apps/.../dialog.json) always points at a resource.
        if (clean.contains("/") || clean.contains("\\")) {
            return TargetKind.RESOURCE;
        }
        // Dynamic expressions and option syntax are not statically resolvable.
        if (hasUnresolvedSyntax(clean)) {
            return TargetKind.UNRESOLVED;
        }
        if (hasResourceExtension(clean)) {
            return TargetKind.RESOURCE;
        }
        if (!JAVA_NAME_PATTERN.matcher(clean).matches()) {
            return TargetKind.UNRESOLVED;
        }
        // A bare lowercase word (`data`) is neither a Java type nor a known resource.
        if (!clean.contains(".") && !Character.isUpperCase(clean.charAt(0))) {
            return TargetKind.UNRESOLVED;
        }
        return TargetKind.JAVA_CLASS;
    }

    public boolean isJavaClass() {
        return kind() == TargetKind.JAVA_CLASS;
    }

    public boolean isResource() {
        return kind() == TargetKind.RESOURCE;
    }

    public boolean isUnresolved() {
        return kind() == TargetKind.UNRESOLVED;
    }

    /** The Java class name to resolve, or {@code null} when the target is not a Java type. */
    public String javaClassName() {
        if (!isJavaClass()) {
            return null;
        }
        String clean = target.strip();
        if (clean.endsWith(".class")) {
            clean = clean.substring(0, clean.length() - 6);
        }
        return clean;
    }

    private static boolean hasUnresolvedSyntax(String clean) {
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '$' || c == '{' || c == '}' || c == '@' || c == '(' || c == ')'
                    || Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasResourceExtension(String clean) {
        int lastDot = clean.lastIndexOf('.');
        int lastSlash = clean.lastIndexOf('/');
        if (lastDot <= 0 || lastDot < lastSlash || lastDot == clean.length() - 1) {
            return false;
        }
        String extension = clean.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        return RESOURCE_EXTENSIONS.contains(extension);
    }
}
