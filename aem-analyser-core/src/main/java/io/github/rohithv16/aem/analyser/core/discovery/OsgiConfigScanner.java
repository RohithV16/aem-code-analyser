package io.github.rohithv16.aem.analyser.core.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.rohithv16.aem.analyser.core.parser.config.ConfigFilePidResolver;

/**
 * Precision OSGi configuration file scanner enforcing the AEM Cloud Service strict anchor,
 * the legacy AEM 6.5 fallback anchor and the 3-layer defense-in-depth hazard elimination:
 * <ol>
 *   <li>Layer 1: strict path confinement inside jcr_root osgiconfig subtrees</li>
 *   <li>Layer 2: filename &amp; extension whitelist / tooling blacklist</li>
 *   <li>Layer 3: OSGi PID grammar validation</li>
 * </ol>
 */
public final class OsgiConfigScanner {

    /** Tooling files that must never be ingested as OSGi configurations. */
    private static final Set<String> BLACKLISTED_FILENAMES = Set.of(
            "lombok.config", ".editorconfig", "checkstyle.config", ".checkstyle", ".eslintignore");

    /** Allowed configuration file extensions. */
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".cfg.json", ".config", ".cfg", ".content.xml");

    /** AEM as a Cloud Service strict anchor. */
    private static final Pattern CLOUD_ANCHOR = Pattern.compile(
            "^src/main/content/jcr_root/apps/(?<app>[^/]+)/osgiconfig/(?<folder>config(?:\\.[a-zA-Z0-9_-]+)*)/(?<filename>[^/]+)$");

    /** Legacy AEM 6.5 fallback anchor. */
    private static final Pattern LEGACY_ANCHOR = Pattern.compile(
            "^src/main/content/jcr_root/apps/(?<app>[^/]+)/(?<folder>config(?:\\.[a-zA-Z0-9_-]+)*)/(?<filename>[^/]+)$");

    public record DiscoveredConfig(Path absolutePath, String relativePath, String folderName,
                                   Set<String> runmodes, String pid, String factorySubname,
                                   boolean isFactory, boolean factoryAmbiguous) {
    }

    public List<DiscoveredConfig> scanModule(Path moduleDirectory) {
        return scanModule(moduleDirectory, new io.github.rohithv16.aem.analyser.core.util.DiagnosticSink());
    }

    public List<DiscoveredConfig> scanModule(Path moduleDirectory,
                                             io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics) {
        Path jcrRoot = moduleDirectory.resolve("src/main/content/jcr_root/apps");
        if (!Files.isDirectory(jcrRoot)) {
            return List.of();
        }
        return scanModule(FileInventory.scan(jcrRoot), moduleDirectory, diagnostics);
    }

    /**
     * Scans one module's {@code jcr_root/apps} tree from the run's shared inventory.
     *
     * <p>Discovery needs the config set before module classification, so the inventory is built at
     * the start of the run and handed in here instead of each module walking its own subtree.</p>
     */
    public List<DiscoveredConfig> scanModule(FileInventory inventory, Path moduleDirectory,
                                             io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics) {
        Path jcrRoot = moduleDirectory.resolve("src/main/content/jcr_root/apps");
        if (!Files.isDirectory(jcrRoot)) {
            return List.of();
        }
        List<DiscoveredConfig> results = new ArrayList<>();
        for (Path file : inventory.filesUnder(jcrRoot, this::isCandidateExtension)) {
            accept(file, moduleDirectory).ifPresent(results::add);
        }
        return results;
    }

    private boolean isCandidateExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private java.util.Optional<DiscoveredConfig> accept(Path file, Path moduleDirectory) {
        String moduleRelative = moduleDirectory.relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');

        // Layer 2: blacklist explicit tooling files
        String fileName = file.getFileName().toString();
        if (BLACKLISTED_FILENAMES.contains(fileName.toLowerCase(Locale.ROOT))) {
            return java.util.Optional.empty();
        }

        String folder = resolveConfigFolder(moduleRelative);
        if (folder == null) {
            return java.util.Optional.empty();
        }

        // Layer 2b: .content.xml must be a sling:OsgiConfig node
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".content.xml") && !isSlingOsgiConfigXml(file)) {
            return java.util.Optional.empty();
        }

        // Layer 3: OSGi PID grammar validation
        String baseName = stripExtension(fileName);
        boolean isCfgJson = fileName.toLowerCase(Locale.ROOT).endsWith(".cfg.json");
        ConfigFilePidResolver.ResolvedPid resolved = ConfigFilePidResolver.resolve(baseName, isCfgJson);
        if (resolved.pid() == null) {
            return java.util.Optional.empty();
        }
        Set<String> runmodes = resolveRunmodes(folder);
        return java.util.Optional.of(new DiscoveredConfig(file.toAbsolutePath().normalize(),
                moduleRelative, folder, runmodes, resolved.pid(), resolved.factorySubname(),
                resolved.isFactory(), resolved.ambiguousHyphen()));
    }

    /**
     * Resolves the runmode folder for the file ({@code osgiconfig/config.author.dev/...}).
     */
    private String resolveConfigFolder(String moduleRelative) {
        Matcher cloud = CLOUD_ANCHOR.matcher(moduleRelative);
        if (cloud.matches()) {
            return cloud.group("folder");
        }
        Matcher legacy = LEGACY_ANCHOR.matcher(moduleRelative);
        if (legacy.matches()) {
            // reject nested pipeline folders such as config/rewriter/asset/.content.xml
            String fileDir = moduleRelative.substring(0, moduleRelative.lastIndexOf('/'));
            if (!fileDir.endsWith(legacy.group("folder"))) {
                return null;
            }
            return legacy.group("folder");
        }
        return null;
    }

    private boolean isSlingOsgiConfigXml(Path file) {
        try {
            String content = Files.readString(file);
            return content.contains("sling:OsgiConfig");
        } catch (IOException e) {
            return false;
        }
    }

    private String stripExtension(String fileName) {
        return ConfigFilePidResolver.stripExtension(fileName);
    }

    public Set<String> resolveRunmodes(String folderName) {
        if (folderName == null || !folderName.startsWith("config")) {
            return Set.of();
        }
        String rest = folderName.substring("config".length());
        if (rest.isEmpty()) {
            return Set.of();
        }
        String[] tokens = rest.split("\\.");
        Set<String> runmodes = new java.util.LinkedHashSet<>();
        for (String token : tokens) {
            if (!token.isBlank()) {
                runmodes.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(runmodes.isEmpty() ? Set.of() : runmodes);
    }
}
