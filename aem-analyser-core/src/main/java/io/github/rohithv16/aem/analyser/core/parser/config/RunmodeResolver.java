package io.github.rohithv16.aem.analyser.core.parser.config;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Computes the effective property set for one PID across runmode folders.
 *
 * <p>Precedence: a config with no runmodes is the base; configs whose runmode set is a subset of the
 * active runmodes override it. More specific runmode sets win, so
 * {@code config < config.author < config.author.dev}.
 */
public final class RunmodeResolver {

    private RunmodeResolver() {
    }

    public static Map<String, String> effectiveProperties(
            String pid,
            Collection<OsgiConfigDefinition> configs,
            Set<String> activeRunmodes) {
        Set<String> active = activeRunmodes == null ? Set.of() : activeRunmodes;
        Collection<OsgiConfigDefinition> candidates = configs == null ? java.util.List.of() : configs;

        return candidates.stream()
                .filter(config -> pid != null && pid.equals(config.pid()))
                .filter(config -> config.runmodes().isEmpty() || active.containsAll(config.runmodes()))
                .sorted(Comparator.comparingInt((OsgiConfigDefinition config) -> config.runmodes().size())
                        .thenComparing(config -> String.join(".", config.runmodes())))
                .collect(LinkedHashMap::new,
                        (merged, config) -> config.properties().values().forEach(property ->
                                merged.put(property.baseKey(), property.rawValue())),
                        LinkedHashMap::putAll);
    }
}
