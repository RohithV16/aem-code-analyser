package io.github.aemtools.analyser.core.parser.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared OSGi configuration file name / PID resolution:
 * <ul>
 *   <li>OSGi PID grammar validation (at least one dot)</li>
 *   <li>Factory delimiter splitting: tilde for RFC 724 (.cfg.json), tilde-then-hyphen for legacy .config</li>
 * </ul>
 */
public final class ConfigFilePidResolver {

    private static final Pattern PID_GRAMMAR = Pattern.compile(
            "^(?<pid>[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)(~(?<namedFactory>[a-zA-Z0-9_.-]+)|-(?<legacyFactory>[a-zA-Z0-9_.-]+))?$");

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
        if (baseName == null) {
            return new ResolvedPid(null, null, false, false);
        }
        Matcher matcher = PID_GRAMMAR.matcher(baseName);
        if (!matcher.matches()) {
            return new ResolvedPid(null, null, false, false);
        }
        String namedFactory = matcher.group("namedFactory");
        String legacyFactory = matcher.group("legacyFactory");
        if (namedFactory != null) {
            // RFC 724: the tilde is always a factory delimiter
            return new ResolvedPid(matcher.group("pid"), namedFactory, true, false);
        }
        if (!isCfgJson && legacyFactory != null) {
            // Legacy .config: the hyphen is a factory delimiter (graph-time disambiguation
            // may be required when the suffix itself contains dots)
            return new ResolvedPid(matcher.group("pid"), legacyFactory, true, legacyFactory.contains("."));
        }
        // No factory delimiter for this format (hyphens are legal PID characters in
        // .cfg.json names): the entire file name is the PID
        return new ResolvedPid(baseName, null, false, false);
    }

    public static boolean isValidPid(String baseName) {
        return baseName != null && PID_GRAMMAR.matcher(baseName).matches();
    }
}
