package io.github.aemtools.analyser.core.discovery;

import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Collects bundle {@code Export-Package} declarations from:
 * <ul>
 *   <li>{@code bnd.bnd} descriptor files</li>
 *   <li>{@code <Export-Package>} instructions of bnd/bundle Maven plugin configurations</li>
 * </ul>
 * Bnd directives (e.g. {@code ;version="1.0.0"}) are stripped.
 */
public final class ExportPackageCollector {

    private static final Pattern BND_HEADER_PATTERN = Pattern.compile("(?m)^\\s*Export-Package\\s*:\\s*([^\\n]*)");

    private ExportPackageCollector() {
    }

    public static Set<String> collect(Model model, Path moduleDirectory) {
        Set<String> packages = new LinkedHashSet<>();
        packages.addAll(fromBndFile(moduleDirectory));
        packages.addAll(fromPluginConfiguration(model, "bnd-maven-plugin"));
        packages.addAll(fromPluginConfiguration(model, "maven-bundle-plugin"));
        return Set.copyOf(packages);
    }

    private static Set<String> fromBndFile(Path moduleDirectory) {
        Set<String> result = new LinkedHashSet<>();
        Path bnd = moduleDirectory.resolve("bnd.bnd");
        if (!Files.isRegularFile(bnd)) {
            return result;
        }
        try {
            String content = Files.readString(bnd);
            Matcher matcher = BND_HEADER_PATTERN.matcher(content);
            if (matcher.find()) {
                result.addAll(splitPackageList(matcher.group(1)));
            }
        } catch (IOException e) {
            // unreadable bnd file: skip
        }
        return result;
    }

    private static Set<String> fromPluginConfiguration(Model model, String pluginArtifactId) {
        Set<String> result = new LinkedHashSet<>();
        List<Plugin> plugins = new ArrayList<>();
        if (model.getBuild() != null) {
            plugins.addAll(model.getBuild().getPlugins());
        }
        model.getProfiles().forEach(p -> {
            if (p.getBuild() != null) {
                plugins.addAll(p.getBuild().getPlugins());
            }
        });
        plugins.stream()
                .filter(plugin -> pluginArtifactId.equals(plugin.getArtifactId()))
                .map(Plugin::getConfiguration)
                .filter(Xpp3Dom.class::isInstance)
                .map(Xpp3Dom.class::cast)
                .map(dom -> dom.getChild("Export-Package"))
                .filter(java.util.Objects::nonNull)
                .forEach(dom -> result.addAll(splitPackageList(dom.getValue())));
        return result;
    }

    /**
     * Splits a bnd-style package list, stripping trailing directives like {@code ;version=1.0}.
     */
    static List<String> splitPackageList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String entry : raw.split("\\s*,\\s*")) {
            String pkg = entry.strip();
            int directive = pkg.indexOf(';');
            if (directive >= 0) {
                pkg = pkg.substring(0, directive).strip();
            }
            if (!pkg.isEmpty() && pkg.matches("[a-zA-Z0-9_.*]+")) {
                result.add(pkg);
            }
        }
        return result;
    }

    /**
     * Bnd-style hierarchical wildcard match: {@code com.foo.*} matches {@code com.foo.bar.baz}.
     */
    public static boolean matchesExportPattern(String packageName, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String trimmed = pattern.strip();
        if (trimmed.endsWith(".*")) {
            String prefix = trimmed.substring(0, trimmed.length() - 2);
            return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
        }
        return packageName.equals(trimmed);
    }

    public static boolean isPackageExported(String packageName, Set<String> exportedPatterns) {
        return exportedPatterns.stream().anyMatch(pattern -> matchesExportPattern(packageName, pattern));
    }

    public static Optional<String> findOsgiExportAnnotation(Stream<String> lines) {
        return lines.filter(l -> l.contains("org.osgi.annotation.bundle.Export"))
                    .findFirst()
                    .map(l -> l.strip().toLowerCase(Locale.ROOT));
    }
}
