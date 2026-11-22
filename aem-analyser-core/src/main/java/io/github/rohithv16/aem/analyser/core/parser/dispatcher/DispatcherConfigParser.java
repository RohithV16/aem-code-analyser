package io.github.rohithv16.aem.analyser.core.parser.dispatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the dispatcher module of an AEM project: Apache {@code Include} directives, {@code $include}
 * directives inside dispatcher {@code .any} files, {@code <VirtualHost>} blocks and the legacy
 * monolithic {@code dispatcher.any}.
 *
 * <p>The reader is deliberately line based. Dispatcher configuration is Apache-style directive text,
 * not XML, and every finding has to point at the directive line, so a full Apache grammar buys
 * nothing a small scanner does not already have. Only directives that can be resolved statically are
 * reported: a wildcard or variable target ({@code Include conf.d/enabled_vhosts/*.vhost},
 * {@code $include "${FARM_FILE}"}) is skipped because the value only exists inside the running
 * container. That guard is what keeps a stock AEM SDK dispatcher module, whose {@code enabled_*}
 * folders only hold symlinks at deploy time, free of findings.
 */
public final class DispatcherConfigParser {

    /**
     * @param file            absolute file declaring the vhost
     * @param line            1-based line of the opening {@code <VirtualHost>}
     * @param serverName      the {@code ServerName} value, or null when the block declares none
     * @param hasDocumentRoot whether the block declares a {@code DocumentRoot}
     */
    public record VirtualHost(Path file, int line, String serverName, boolean hasDocumentRoot) {
    }

    /**
     * @param file      absolute file declaring the include
     * @param line      1-based line of the directive
     * @param target    the include target exactly as written, unquoted
     * @param directive {@code Include} or {@code $include}
     * @param resolved  whether the target names at least one file on disk
     */
    public record IncludeDirective(Path file, int line, String target, String directive, boolean resolved) {
    }

    /**
     * @param moduleRoots              dispatcher module directories found under the project root
     * @param virtualHosts             every live {@code <VirtualHost>} block
     * @param includes                 every statically resolvable {@code Include}/{@code $include}
     * @param monolithicDispatcherAny  {@code dispatcher.any} files outside {@code conf.dispatcher.d}
     */
    public record DispatcherConfig(List<Path> moduleRoots,
                                   List<VirtualHost> virtualHosts,
                                   List<IncludeDirective> includes,
                                   List<Path> monolithicDispatcherAny) {

        public DispatcherConfig {
            moduleRoots = List.copyOf(moduleRoots);
            virtualHosts = List.copyOf(virtualHosts);
            includes = List.copyOf(includes);
            monolithicDispatcherAny = List.copyOf(monolithicDispatcherAny);
        }

        public static DispatcherConfig empty() {
            return new DispatcherConfig(List.of(), List.of(), List.of(), List.of());
        }
    }

    /** File types that can carry dispatcher/apache directives. */
    private static final Set<String> CONFIG_SUFFIXES =
            Set.of(".conf", ".vhost", ".vars", ".any", ".farm", ".rules");

    private static final String[] SKIP_SEGMENTS = {"/target/", "/node_modules/", "/.git/", "/vault-work/"};

    private static final Pattern INCLUDE = Pattern.compile(
            "^\\s*(Include|IncludeOptional)\\b\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOLLAR_INCLUDE = Pattern.compile(
            "^\\s*\\$include\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VHOST_START = Pattern.compile(
            "^\\s*<VirtualHost\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern VHOST_END = Pattern.compile(
            "</VirtualHost\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVER_NAME = Pattern.compile(
            "^\\s*ServerName\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENT_ROOT = Pattern.compile(
            "^\\s*DocumentRoot\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    /** Depth bound on the search for module directories; AEM reactors nest two or three levels. */
    private static final int MAX_MODULE_DEPTH = 6;

    /**
     * Scans every dispatcher module below {@code projectRoot}.
     *
     * @param projectRoot reactor root (or a single module directory)
     * @return the parsed configuration; empty when the project has no dispatcher module
     */
    public DispatcherConfig parse(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return DispatcherConfig.empty();
        }
        List<Path> moduleRoots = dispatcherRoots(projectRoot);
        List<VirtualHost> vhosts = new ArrayList<>();
        List<IncludeDirective> includes = new ArrayList<>();
        List<Path> monolithic = new ArrayList<>();

        for (Path moduleRoot : moduleRoots) {
            Path serverRoot = moduleRoot.resolve("src");
            if (!Files.isDirectory(serverRoot)) {
                serverRoot = moduleRoot;
            }
            for (Path file : configFiles(moduleRoot)) {
                if (isRuntimeProvided(serverRoot, file)) {
                    continue;
                }
                parseFile(file, serverRoot, vhosts, includes);
                if (file.getFileName().toString().equalsIgnoreCase("dispatcher.any")
                        && !isInDispatcherConfDirectory(file)) {
                    monolithic.add(file);
                }
            }
        }
        return new DispatcherConfig(moduleRoots, vhosts, includes, monolithic);
    }

    private void parseFile(Path file, Path serverRoot, List<VirtualHost> vhosts,
                           List<IncludeDirective> includes) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return;
        }
        boolean inVhost = false;
        int vhostLine = 0;
        String serverName = null;
        boolean hasDocumentRoot = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = stripComment(lines.get(i)).strip();
            if (line.isEmpty()) {
                continue;
            }
            includeDirective(file, serverRoot, line, i + 1, includes);

            if (!inVhost && VHOST_START.matcher(line).find()) {
                inVhost = true;
                vhostLine = i + 1;
                serverName = null;
                hasDocumentRoot = false;
                continue;
            }
            if (inVhost) {
                if (VHOST_END.matcher(line).find()) {
                    vhosts.add(new VirtualHost(file, vhostLine, serverName, hasDocumentRoot));
                    inVhost = false;
                    continue;
                }
                Matcher name = SERVER_NAME.matcher(line);
                if (name.matches() && serverName == null) {
                    serverName = cleanValue(name.group(1));
                } else if (DOCUMENT_ROOT.matcher(line).find()) {
                    hasDocumentRoot = true;
                }
            }
        }
        // An unterminated block is still a block for the checks the rule performs.
        if (inVhost) {
            vhosts.add(new VirtualHost(file, vhostLine, serverName, hasDocumentRoot));
        }
    }

    private void includeDirective(Path file, Path serverRoot, String line, int lineNumber,
                                  List<IncludeDirective> includes) {
        Matcher dollar = DOLLAR_INCLUDE.matcher(line);
        if (dollar.matches()) {
            addInclude(file, serverRoot, lineNumber, dollar.group(1), "$include", true, includes);
            return;
        }
        Matcher apache = INCLUDE.matcher(line);
        if (apache.matches()) {
            String directive = apache.group(1);
            // IncludeOptional exists precisely so a missing target is not an error.
            if (!directive.equalsIgnoreCase("Include")) {
                return;
            }
            addInclude(file, serverRoot, lineNumber, apache.group(2), "Include", false, includes);
        }
    }

    private void addInclude(Path file, Path serverRoot, int lineNumber, String rawTarget,
                            String directive, boolean relativeToIncludingFile,
                            List<IncludeDirective> includes) {
        String target = cleanValue(rawTarget);
        if (target.isEmpty() || isDynamic(target)) {
            return;
        }
        boolean resolved = resolves(file, serverRoot, target, relativeToIncludingFile);
        includes.add(new IncludeDirective(file, lineNumber, target, directive, resolved));
    }

    private boolean resolves(Path file, Path serverRoot, String target, boolean relativeToIncludingFile) {
        try {
            if (target.startsWith("/")) {
                return Files.isRegularFile(Path.of(target));
            }
            Path parent = file.getParent();
            if (relativeToIncludingFile) {
                return existsUnder(parent, target) || existsUnder(serverRoot, target);
            }
            return existsUnder(serverRoot, target) || existsUnder(parent, target);
        } catch (InvalidPathException malformed) {
            // A target the JDK cannot even represent is not a resolvable file either.
            return true;
        }
    }

    private boolean existsUnder(Path base, String target) {
        return base != null && Files.isRegularFile(base.resolve(target).normalize());
    }

    /**
     * Wildcard and variable targets are resolved by Apache at request time, so static analysis must
     * not call them broken. This covers the stock SDK {@code enabled_vhosts/*.vhost} include and any
     * {@code ${...}} indirection.
     */
    private static boolean isDynamic(String target) {
        return target.indexOf('*') >= 0 || target.indexOf('?') >= 0 || target.indexOf('$') >= 0
                || target.indexOf('[') >= 0;
    }

    /** Removes a trailing {@code # comment}, honouring single and double quotes. */
    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** Strips whitespace and one layer of matching quotes from a directive argument. */
    private static String cleanValue(String value) {
        String cleaned = value.strip();
        if (cleaned.length() >= 2) {
            char first = cleaned.charAt(0);
            char last = cleaned.charAt(cleaned.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                cleaned = cleaned.substring(1, cleaned.length() - 1).strip();
            }
        }
        return cleaned;
    }

    /** Module directories named {@code dispatcher} that carry a conf.d or conf.dispatcher.d tree. */
    private List<Path> dispatcherRoots(Path projectRoot) {
        Set<Path> roots = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.walk(projectRoot, MAX_MODULE_DEPTH)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> !isSkipped(path))
                    .filter(path -> path.getFileName() != null
                            && path.getFileName().toString().equalsIgnoreCase("dispatcher"))
                    .filter(this::hasDispatcherTree)
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(roots::add);
        } catch (IOException ignored) {
            // An unreadable tree simply yields no dispatcher module.
        }
        // A nested match inside another module would double-report every directive.
        List<Path> flat = new ArrayList<>();
        for (Path candidate : roots) {
            boolean nested = roots.stream().anyMatch(other -> !other.equals(candidate)
                    && candidate.startsWith(other));
            if (!nested) {
                flat.add(candidate);
            }
        }
        return flat;
    }

    private boolean hasDispatcherTree(Path dispatcherDir) {
        Path src = dispatcherDir.resolve("src");
        return Files.isDirectory(src.resolve("conf.d"))
                || Files.isDirectory(src.resolve("conf.dispatcher.d"))
                || Files.isDirectory(dispatcherDir.resolve("conf.d"));
    }

    private List<Path> configFiles(Path moduleRoot) {
        Path base = Files.isDirectory(moduleRoot.resolve("src")) ? moduleRoot.resolve("src") : moduleRoot;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(base)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !isSkipped(path))
                    .filter(DispatcherConfigParser::hasConfigSuffix)
                    .sorted()
                    .forEach(files::add);
        } catch (IOException ignored) {
            // Partial read is better than none.
        }
        return files;
    }

    /**
     * Two files are shipped by the AEM runtime, not by the project: {@code conf.d/dispatcher_vhost.conf}
     * and {@code conf.dispatcher.d/dispatcher.any}. They include runtime paths that do not exist in
     * source control ({@code conf.d/variables/default.vars}), and in the SDK template {@code enabled_*}
     * folders contain symlinks into {@code available_*}, which the parser does not follow. Both would
     * otherwise make every stock AEM project fail the rule.
     */
    private static boolean isRuntimeProvided(Path serverRoot, Path file) {
        Path relative;
        try {
            relative = serverRoot.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize());
        } catch (IllegalArgumentException differentRoot) {
            return false;
        }
        String path = relative.toString().replace('\\', '/');
        return path.equals("conf.d/dispatcher_vhost.conf")
                || path.equals("conf.dispatcher.d/dispatcher.any");
    }

    private static boolean hasConfigSuffix(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return CONFIG_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private static boolean isSkipped(Path path) {
        String normalized = path.toString().replace('\\', '/');
        for (String segment : SKIP_SEGMENTS) {
            if (normalized.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /** {@code conf.dispatcher.d/dispatcher.any} is the split (Cloud) layout, not the legacy one. */
    private static boolean isInDispatcherConfDirectory(Path file) {
        Path parent = file.getParent();
        return parent != null && parent.getFileName() != null
                && parent.getFileName().toString().equals("conf.dispatcher.d");
    }
}
