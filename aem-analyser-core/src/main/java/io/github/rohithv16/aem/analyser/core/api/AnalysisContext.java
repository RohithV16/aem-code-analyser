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
 * @param dependencyJarRoots     additional JAR roots for the bytecode indexer (defaults to the local Maven repository)
 * @param ignoredServices        service implementation class names whitelisted by configuration
 * @param ignoredConfigPaths     config file path patterns (reactor-relative, glob) excluded from analysis
 * @param enableSonar            whether embedded SonarJava analysis is enabled (default: true)
 * @param sonarRules             SonarJava ruleset filter (default: "default")
 */
public record AnalysisContext(
        Path projectRoot,
        List<String> activeProfiles,
        int threads,
        List<Path> dependencyJarRoots,
        Set<String> ignoredServices,
        Set<String> ignoredConfigPaths,
        boolean enableSonar,
        String sonarRules) {

    public AnalysisContext {
        projectRoot = projectRoot.toAbsolutePath().normalize();
        activeProfiles = List.copyOf(activeProfiles == null ? List.of() : activeProfiles);
        dependencyJarRoots = List.copyOf(dependencyJarRoots == null ? List.of() : dependencyJarRoots);
        ignoredServices = Set.copyOf(ignoredServices == null ? Set.of() : ignoredServices);
        ignoredConfigPaths = Set.copyOf(ignoredConfigPaths == null ? Set.of() : ignoredConfigPaths);
        sonarRules = (sonarRules == null || sonarRules.isBlank()) ? "default" : sonarRules.trim();
    }

    public AnalysisContext(
            Path projectRoot,
            List<String> activeProfiles,
            int threads,
            List<Path> dependencyJarRoots,
            Set<String> ignoredServices,
            Set<String> ignoredConfigPaths) {
        this(projectRoot, activeProfiles, threads, dependencyJarRoots, ignoredServices, ignoredConfigPaths, true, "default");
    }

    public static AnalysisContext of(Path projectRoot) {
        return new AnalysisContext(projectRoot, List.of(), 0, List.of(), Set.of(), Set.of(), true, "default");
    }

    public int workerThreads() {
        return threads > 0 ? threads : Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    }

    public Builder toBuilder() {
        return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots, ignoredServices, ignoredConfigPaths, enableSonar, sonarRules);
    }

    public static Builder builder(Path projectRoot) {
        return new Builder(projectRoot, List.of(), 0, List.of(), Set.of(), Set.of(), true, "default");
    }

    public record Builder(
            Path projectRoot,
            List<String> activeProfiles,
            int threads,
            List<Path> dependencyJarRoots,
            Set<String> ignoredServices,
            Set<String> ignoredConfigPaths,
            boolean enableSonar,
            String sonarRules) {

        public Builder(
                Path projectRoot,
                List<String> activeProfiles,
                int threads,
                List<Path> dependencyJarRoots,
                Set<String> ignoredServices,
                Set<String> ignoredConfigPaths) {
            this(projectRoot, activeProfiles, threads, dependencyJarRoots, ignoredServices, ignoredConfigPaths, true, "default");
        }

        public Builder activeProfiles(List<String> profiles) {
            return new Builder(projectRoot, List.copyOf(profiles), threads, dependencyJarRoots, ignoredServices, ignoredConfigPaths, enableSonar, sonarRules);
        }

        public Builder threads(int threads) {
            return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots, ignoredServices, ignoredConfigPaths, enableSonar, sonarRules);
        }

        public Builder dependencyJarRoots(List<Path> roots) {
            return new Builder(projectRoot, activeProfiles, threads, List.copyOf(roots), ignoredServices, ignoredConfigPaths, enableSonar, sonarRules);
        }

        public Builder ignoredServices(Set<String> ignored) {
            return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots, Set.copyOf(ignored), ignoredConfigPaths, enableSonar, sonarRules);
        }

        public Builder ignoredConfigPaths(Set<String> patterns) {
            return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots, ignoredServices, Set.copyOf(patterns), enableSonar, sonarRules);
        }

        public Builder enableSonar(boolean enableSonar) {
            return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots, ignoredServices, ignoredConfigPaths, enableSonar, sonarRules);
        }

        public Builder sonarRules(String sonarRules) {
            return new Builder(projectRoot, activeProfiles, threads, dependencyJarRoots, ignoredServices, ignoredConfigPaths, enableSonar, sonarRules);
        }

        public AnalysisContext build() {
            return new AnalysisContext(projectRoot, activeProfiles, threads, dependencyJarRoots, ignoredServices, ignoredConfigPaths, enableSonar, sonarRules);
        }
    }
}
