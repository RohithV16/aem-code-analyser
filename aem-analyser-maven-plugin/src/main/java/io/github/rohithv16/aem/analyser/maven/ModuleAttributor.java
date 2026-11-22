package io.github.rohithv16.aem.analyser.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issue #44: attributes a violated file to the reactor module that owns it.
 *
 * <p>Violation paths are documented as reactor-root-relative ({@code core/src/main/java/X.java}), so
 * a module owns a finding when the path starts with that module's own root-relative directory. The
 * <em>longest</em> matching prefix wins, which is what keeps a nested module
 * ({@code all/src/...}) from being swallowed by its parent ({@code all}).
 *
 * <p>The reactor root POM is an aggregator, not a code module: it owns no sources of its own, so it
 * never claims a finding by prefix. Whatever no nested module claims — a file outside every module,
 * or a finding attributed to a path that no longer exists — lands in {@link #REACTOR_BUCKET}
 * instead of vanishing from the per-module output. A single-module build (no nested modules at all)
 * is the exception: there the root module really does own the tree, so it claims everything.
 */
final class ModuleAttributor {

    /** Bucket for findings that belong to no module: they must still be published. */
    static final String REACTOR_BUCKET = "_reactor";

    /** One reactor module: its artifactId and its root-relative directory ('' for the root POM). */
    record Module(String artifactId, String relativePath) {
    }

    private final List<Module> modules;
    private final boolean nestedModules;

    private ModuleAttributor(List<Module> modules) {
        this.modules = List.copyOf(modules);
        this.nestedModules = modules.stream().anyMatch(module -> !module.relativePath().isEmpty());
    }

    /**
     * Builds an attributor from already-normalised modules, longest path first.
     *
     * <p>Duplicates are dropped (a reactor cannot contain the same artifactId twice, but a test or a
     * synthetic session can), keeping the first occurrence so attribution stays deterministic.
     */
    static ModuleAttributor of(List<Module> modules) {
        Map<String, Module> byArtifact = new LinkedHashMap<>();
        for (Module module : modules == null ? List.<Module>of() : modules) {
            String artifactId = module.artifactId() == null || module.artifactId().isBlank()
                    ? REACTOR_BUCKET : module.artifactId().trim();
            String relativePath = normalisePath(module.relativePath());
            byArtifact.putIfAbsent(artifactId, new Module(artifactId, relativePath));
        }
        List<Module> ordered = new ArrayList<>(byArtifact.values());
        // Longest path first: 'all/src' must beat 'all'. Ties break on the artifactId so the order
        // does not depend on session ordering.
        ordered.sort(Comparator
                .comparingInt((Module module) -> module.relativePath().length()).reversed()
                .thenComparing(Module::artifactId));
        return new ModuleAttributor(ordered);
    }

    /** The modules this attributor knows about, longest path first. */
    List<Module> modules() {
        return modules;
    }

    /**
     * @param filePath reactor-root-relative violation path
     * @return the owning module's artifactId, or {@link #REACTOR_BUCKET}
     */
    String moduleFor(String filePath) {
        String path = normalisePath(filePath);
        if (!path.isEmpty()) {
            for (Module module : modules) {
                String prefix = module.relativePath();
                if (!prefix.isEmpty() && (path.equals(prefix) || path.startsWith(prefix + "/"))) {
                    return module.artifactId();
                }
            }
        }
        if (!nestedModules && !modules.isEmpty()) {
            // Single-module build: the only module is the analysed tree itself.
            return modules.get(0).artifactId();
        }
        return REACTOR_BUCKET;
    }

    /** True when this reactor has modules below the root POM. */
    boolean isReactor() {
        return nestedModules;
    }

    /**
     * @param root    the analysed reactor root
     * @param basedir a module's base directory
     * @return the '/'-separated root-relative directory, or {@code null} when the module is outside
     *         the analysed tree and therefore cannot own any of its files
     */
    static String relativePathOf(Path root, Path basedir) {
        if (root == null || basedir == null) {
            return null;
        }
        Path normalisedRoot = root.toAbsolutePath().normalize();
        Path normalisedBase = basedir.toAbsolutePath().normalize();
        if (!normalisedBase.startsWith(normalisedRoot)) {
            return null;
        }
        Path relative = normalisedRoot.relativize(normalisedBase);
        String path = relative.toString();
        if (File.separatorChar != '/') {
            path = path.replace(File.separatorChar, '/');
        }
        return normalisePath(path);
    }

    /** Strips './', converts separators and trims a trailing '/' — never touches path identity. */
    static String normalisePath(String path) {
        if (path == null) {
            return "";
        }
        String normalised = path.trim().replace('\\', '/');
        while (normalised.startsWith("./")) {
            normalised = normalised.substring(2);
        }
        while (normalised.endsWith("/")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        return ".".equals(normalised) ? "" : normalised;
    }
}
