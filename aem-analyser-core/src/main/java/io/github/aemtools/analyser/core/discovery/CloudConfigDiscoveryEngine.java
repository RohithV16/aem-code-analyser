package io.github.aemtools.analyser.core.discovery;

import io.github.aemtools.analyser.core.model.discovery.CloudConfigDescriptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Non-Maven Cloud Configuration discovery engine (FEAT-DISC-03).
 * Scans the repository root {@code config/} directory for declarative CDN,
 * WAF, rate-limiting and request-routing descriptors outside the Maven reactor.
 */
public final class CloudConfigDiscoveryEngine {

    private static final Set<String> CLOUD_CONFIG_NAMES = Set.of(
            "cdn.yaml", "cdn.yml", "waf.yaml", "waf.yml",
            "rate-limit.yaml", "rate-limit.yml",
            "request-routing.yaml", "request-routing.yml");

    public List<CloudConfigDescriptor> discover(Path repoRoot) {
        List<CloudConfigDescriptor> configs = new ArrayList<>();
        Path configDir = repoRoot.resolve("config");
        if (!Files.isDirectory(configDir)) {
            return configs;
        }
        try (Stream<Path> stream = Files.walk(configDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> CLOUD_CONFIG_NAMES.contains(p.getFileName().toString().toLowerCase(Locale.ROOT)))
                  .sorted()
                  .forEach(p -> {
                      String relPath = configDir.relativize(p).toString().replace('\\', '/');
                      String[] segments = relPath.split("/");
                      String env = segments.length > 1 ? segments[0].toUpperCase(Locale.ROOT) : "GLOBAL";
                      configs.add(new CloudConfigDescriptor(p.toAbsolutePath().normalize(),
                              repoRoot.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/'),
                              resolveType(p.getFileName().toString()), env));
                  });
        } catch (IOException | UncheckedIOException e) {
            // non-fatal: cloud descriptors are informative only
        }
        return configs;
    }

    private String resolveType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("cdn")) {
            return "cdn";
        }
        if (lower.startsWith("waf")) {
            return "waf";
        }
        if (lower.startsWith("rate-limit")) {
            return "rate-limit";
        }
        if (lower.startsWith("request-routing")) {
            return "request-routing";
        }
        return "unknown";
    }
}
