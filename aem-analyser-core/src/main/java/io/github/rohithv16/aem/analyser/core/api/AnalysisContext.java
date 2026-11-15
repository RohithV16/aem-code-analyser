package io.github.rohithv16.aem.analyser.core.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Immutable input parameters for one analysis execution.
 *
 * @param projectRoot            absolute project root to analyse (Maven reactor root)
 * @param activeProfiles         extra Maven profiles to activate during discovery
 * @param threads                worker thread count for parallel parsing (0 = available processors)
 * @param dependencyJarRoots     additional JAR roots for the bytecode indexer; empty by default
 * @param ignoredServices        service implementation class names whitelisted by configuration
 * @param ignoredConfigPaths     config file path patterns (reactor-relative, glob) excluded from analysis
 * @param enableSonar            whether embedded SonarJava analysis is enabled (default: true)
 * @param sonarRules             SonarJava ruleset filter (default: "default")
 * @param maxFileSizeBytes       maximum bytes per source/config/template file (default: 10 MiB)
 * @param timeoutMillis          overall analysis budget in milliseconds (0 = no timeout)
 * @param changedFiles           when non-empty, only these reactor-relative files are parsed
 */
public record AnalysisContext(
        Path projectRoot,
        List<String> activeProfiles,
        int threads,
        List<Path> dependencyJarRoots,
        Set<String> ignoredServices,
        Set<String> ignoredConfigPaths,
        boolean enableSonar,
        String sonarRules,
        long maxFileSizeBytes,
        long timeoutMillis,
        Set<String> changedFiles) {

    public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    public AnalysisContext {
        projectRoot = projectRoot.toAbsolutePath().normalize();
        activeProfiles = List.copyOf(activeProfiles == null ? List.of() : activeProfiles);
        dependencyJarRoots = List.copyOf(dependencyJarRoots == null ? List.of() : dependencyJarRoots);
        ignoredServices = Set.copyOf(ignoredServices == null ? Set.of() : ignoredServices);
        ignoredConfigPaths = Set.copyOf(ignoredConfigPaths == null ? Set.of() : ignoredConfigPaths);
        sonarRules = (sonarRules == null || sonarRules.isBlank()) ? "default" : sonarRules.trim();
        maxFileSizeBytes = maxFileSizeBytes > 0 ? maxFileSizeBytes : DEFAULT_MAX_FILE_SIZE_BYTES;
        timeoutMillis = Math.max(0, timeoutMillis);
        changedFiles = Set.copyOf(changedFiles == null ? Set.of() : changedFiles);
    }

    public AnalysisContext(
            Path projectRoot,
            List<String> activeProfiles,
            int threads,
            List<Path> dependencyJarRoots,
            Set<String> ignoredServices,
            Set<String> ignoredConfigPaths) {
        this(projectRoot, activeProfiles, threads, dependencyJarRoots, ignoredServices, ignoredConfigPaths,
                true, "default", DEFAULT_MAX_FILE_SIZE_BYTES, 0, Set.of());
    }

    public static AnalysisContext of(Path projectRoot) {
        return new AnalysisContext(projectRoot, List.of(), 0, List.of(), Set.of(), Set.of(),
                true, "default", DEFAULT_MAX_FILE_SIZE_BYTES, 0, Set.of());
    }

    public int workerThreads() {
        return threads > 0 ? threads : Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    }

    public Builder toBuilder() {
        return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots, ignoredServices,
                ignoredConfigPaths, enableSonar, sonarRules, maxFileSizeBytes, timeoutMillis, changedFiles);
    }

    public static Builder builder(Path projectRoot) {
        return new Builder(projectRoot, List.of(), 0, List.of(), Set.of(), Set.of(),
                true, "default", DEFAULT_MAX_FILE_SIZE_BYTES, 0, Set.of());
    }

    public record Builder(
            Path projectRoot,
            List<String> activeProfiles,
            int threads,
            List<Path> dependencyJarRoots,
            Set<String> ignoredServices,
            Set<String> ignoredConfigPaths,
            boolean enableSonar,
            String sonarRules,
            long maxFileSizeBytes,
            long timeoutMillis,
            Set<String> changedFiles) {

        public Builder(
                Path projectRoot,
                List<String> activeProfiles,
                int threads,
                List<Path> dependencyJarRoots,
                Set<String> ignoredServices,
                Set<String> ignoredConfigPaths) {
            this(projectRoot, activeProfiles, threads, dependencyJarRoots, ignoredServices, ignoredConfigPaths,
                    true, "default", DEFAULT_MAX_FILE_SIZE_BYTES, 0, Set.of());
        }

        public Builder activeProfiles(List<String> profiles) {
            return new Builder(projectRoot, List.copyOf(profiles), threads, dependencyJarRoots,
                    ignoredServices, ignoredConfigPaths, enableSonar, sonarRules, maxFileSizeBytes,
                    timeoutMillis, changedFiles);
        }

        public Builder threads(int threads) {
            return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots,
                    ignoredServices, ignoredConfigPaths, enableSonar, sonarRules, maxFileSizeBytes,
                    timeoutMillis, changedFiles);
        }

        public Builder dependencyJarRoots(List<Path> roots) {
            return new Builder(projectRoot, activeProfiles, threads, List.copyOf(roots),
                    ignoredServices, ignoredConfigPaths, enableSonar, sonarRules, maxFileSizeBytes,
                    timeoutMillis, changedFiles);
        }

        public Builder ignoredServices(Set<String> ignored) {
            return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots,
                    Set.copyOf(ignored), ignoredConfigPaths, enableSonar, sonarRules, maxFileSizeBytes,
                    timeoutMillis, changedFiles);
        }

        public Builder ignoredConfigPaths(Set<String> patterns) {
            return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots,
                    ignoredServices, Set.copyOf(patterns), enableSonar, sonarRules, maxFileSizeBytes,
                    timeoutMillis, changedFiles);
        }

        public Builder enableSonar(boolean enableSonar) {
            return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots,
                    ignoredServices, ignoredConfigPaths, enableSonar, sonarRules, maxFileSizeBytes,
                    timeoutMillis, changedFiles);
        }

        public Builder sonarRules(String sonarRules) {
            return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots,
                    ignoredServices, ignoredConfigPaths, enableSonar, sonarRules, maxFileSizeBytes,
                    timeoutMillis, changedFiles);
        }

        public Builder maxFileSizeBytes(long maxFileSizeBytes) {
            return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots,
                    ignoredServices, ignoredConfigPaths, enableSonar, sonarRules, maxFileSizeBytes,
                    timeoutMillis, changedFiles);
        }

        public Builder timeoutMillis(long timeoutMillis) {
            return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots,
                    ignoredServices, ignoredConfigPaths, enableSonar, sonarRules, maxFileSizeBytes,
                    timeoutMillis, changedFiles);
        }

        public Builder changedFiles(Set<String> changedFiles) {
            return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots,
                    ignoredServices, ignoredConfigPaths, enableSonar, sonarRules, maxFileSizeBytes,
                    timeoutMillis, Set.copyOf(changedFiles));
        }

        public AnalysisContext build() {
            return new AnalysisContext(projectRoot, activeProfiles, threads, dependencyJarRoots,
                    ignoredServices, ignoredConfigPaths, enableSonar, sonarRules, maxFileSizeBytes,
                    timeoutMillis, changedFiles);
        }
    }
}
