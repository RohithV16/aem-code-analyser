package io.github.rohithv16.aem.analyser.core.parser.config;

import java.util.regex.Pattern;

/**
 * Shared OSGi configuration file name / PID resolution:
 * <ul>
 *   <li>OSGi PID grammar validation (at least one dot, hyphens allowed)</li>
 *   <li>Factory delimiter splitting: tilde for RFC 724 (.cfg.json), hyphen for legacy .config</li>
 * </ul>
 *
 * <p>Hyphens are legal inside an OSGi PID. For legacy {@code .config} files the hyphen is also
 * the factory delimiter, so resolution is inherently ambiguous when the PID itself contains a
 * hyphen. The resolver always splits on the last hyphen whose prefix is a valid PID and reports
 * {@link ResolvedPid#ambiguousHyphen()} so graph-time disambiguation can revisit the decision.
 */
public final class ConfigFilePidResolver {

    /** OSGi PID grammar: dot-separated segments; letters, digits, underscore and hyphen. */
    private static final Pattern PID_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]+)+$");

    /** Factory subname grammar (legacy and RFC 724). */
    private static final Pattern FACTORY_SUBNAME_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_.-]+$");

    private static final ResolvedPid INVALID = new ResolvedPid(null, null, false, false);

    private ConfigFilePidResolver() {
    }

    /**
     * Resolution result for a configuration file base name (extension stripped).
     *
     * @param pid              the target base PID
     * @param factorySubname   optional factory subname
     * @param isFactory        whether this file is a factory configuration
     * @param ambiguousHyphen  true for legacy .config hyphen splits that need graph-time disambiguation
     */
    public record ResolvedPid(String pid, String factorySubname, boolean isFactory, boolean ambiguousHyphen) {
    }

    public static ResolvedPid resolve(String baseName, boolean isCfgJson) {
        if (baseName == null || baseName.isBlank()) {
            return INVALID;
        }

        // RFC 724: only the tilde is a factory delimiter, for both .cfg.json and legacy .config.
        int tilde = baseName.indexOf('~');
        if (tilde >= 0) {
            String pid = baseName.substring(0, tilde);
            String subname = baseName.substring(tilde + 1);
            if (!isValidPid(pid) || !isValidFactorySubname(subname)) {
                return INVALID;
            }
            return new ResolvedPid(pid, subname, true, false);
        }

        // Legacy .config: a hyphen may be the factory delimiter. Try the last hyphen first so
        // that hyphenated PIDs (my-service.pid-instance) resolve to the longest valid PID.
        if (!isCfgJson) {
            int hyphen = baseName.lastIndexOf('-');
            if (hyphen > 0) {
                String pid = baseName.substring(0, hyphen);
                String subname = baseName.substring(hyphen + 1);
                if (isValidPid(pid) && isValidFactorySubname(subname)) {
                    // A hyphen split is ALWAYS ambiguous for legacy .config files: the suffix may be
                    // a factory instance OR part of a hyphenated PID ("com.example.foo-1").
                    // Graph-time disambiguation re-checks the whole name against component PIDs.
                    return new ResolvedPid(pid, subname, true, true);
                }
            }
        }

        // No usable factory delimiter: hyphens are ordinary PID characters for .cfg.json and
        // for legacy .config files whose hyphen split would not yield a valid PID.
        if (!isValidPid(baseName)) {
            return INVALID;
        }
        return new ResolvedPid(baseName, null, false, false);
    }

    public static boolean isValidPid(String baseName) {
        return baseName != null && PID_PATTERN.matcher(baseName).matches();
    }

    /**
     * Strips a supported OSGi configuration extension from a file name.
     * Longest extension wins so {@code Foo.content.xml} and {@code Foo.cfg.json} resolve correctly.
     */
    public static String stripExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        for (String ext : java.util.List.of(".cfg.json", ".content.xml", ".config", ".cfg")) {
            if (lower.endsWith(ext)) {
                return fileName.substring(0, fileName.length() - ext.length());
            }
        }
        return fileName;
    }

    private static boolean isValidFactorySubname(String subname) {
        return subname != null && !subname.isBlank() && FACTORY_SUBNAME_PATTERN.matcher(subname).matches();
    }
}
