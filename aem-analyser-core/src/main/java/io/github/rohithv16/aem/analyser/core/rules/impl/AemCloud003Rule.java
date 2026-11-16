package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.graph.node.ConfigPropertyNode;
import io.github.rohithv16.aem.analyser.core.model.graph.node.OSGiConfigNode;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Severity;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.model.serviceuser.SubserviceCall;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rule AEM-CLOUD-003: Unmapped Subservice or Unresolved Service User.
 * Cross-references Java service-resolver acquisitions (factory/repository APIs with a subservice)
 * with OSGi ServiceUserMapperImpl.amended configurations and project-generic system user
 * declarations across arbitrary YAML definitions, repoinit scripts, OSGi configs, and JCR content
 * packages.
 */
public final class AemCloud003Rule implements RuleEvaluator {

    private static final String SERVICE_USER_MAPPER_PID_PREFIX =
            "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended";

    private static final Pattern MAPPING_ENTRY_PATTERN = Pattern.compile(
            "([a-zA-Z0-9_.-]+)(?::([a-zA-Z0-9_.-]+))?=\\[?([a-zA-Z0-9_.-]+)\\]?"
    );

    private static final Pattern REPOINIT_USER_PATTERN = Pattern.compile(
            "(?i)(?:create|ensure)\\s+(?:service\\s+)?user\\s+([a-zA-Z0-9_.-]+)"
    );

    private static final Pattern JCR_SYSTEM_USER_TAG = Pattern.compile(
            "<([a-zA-Z0-9_.-]+)[^>]*jcr:primaryType=[\"']rep:SystemUser[\"']"
    );

    private static final Pattern JCR_AUTHORIZABLE_ID = Pattern.compile(
            "rep:authorizableId=[\"']([^\"']+)[\"']"
    );

    private static final Pattern JCR_PRINCIPAL_NAME = Pattern.compile(
            "rep:principalName=[\"']([^\"']+)[\"']"
    );

    private static final Pattern YAML_SERVICE_USERS_SECTION = Pattern.compile(
            "(?i)^\\s*(?:service_users|system_users|serviceusers|systemusers)\\s*:\\s*$"
    );

    private static final Pattern YAML_LIST_ITEM = Pattern.compile(
            "^\\s*-\\s*([a-zA-Z0-9_.-]+)\\s*$"
    );

    private static final Pattern YAML_KEY_PATTERN = Pattern.compile(
            "^\\s*-\\s*([a-zA-Z0-9_.-]+)\\s*:\\s*$"
    );

    private static final Pattern YAML_IS_SYSTEM_USER = Pattern.compile(
            "(?i)(?:isSystemUser|isServiceUser|systemUser|is_service_user|is_system_user)\\s*:\\s*true"
    );

    private static final Pattern YAML_PATH_PATTERN = Pattern.compile(
            "(?i)path\\s*:\\s*['\"]?/home/users/system(?:/([a-zA-Z0-9_.-]+))+['\"]?"
    );

    private static final Pattern YAML_EXPLICIT_SERVICE_USER = Pattern.compile(
            "(?i)(?:service_user|serviceUser)\\s*:\\s*['\"]?([a-zA-Z0-9_.-]+)['\"]?"
    );

    private static final Pattern YAML_NAME_PROPERTY = Pattern.compile(
            "(?i)name\\s*:\\s*['\"]?([a-zA-Z0-9_.-]+)['\"]?"
    );

    private static final Set<String> YAML_RESERVED_KEYS = Set.of(
            "user_config", "ace_config", "group_config", "permission_config",
            "users", "groups", "permissions", "aces", "scripts", "configurations",
            "services", "paths", "actions", "allow", "deny", "name", "path", "user",
            "service_user"
    );

    private static final Set<String> OOTB_SYSTEM_USERS = Set.of(
            "repository-service", "workflow-service", "data-service",
            "content-writer-service", "replication-service", "asset-writer-service",
            "version-manager-service", "tag-garbage-collector", "fd-service",
            "screens-service", "commerce-service", "oauth-service", "dam-update-service",
            "dynamic-media-service", "distribution-service", "indexing-service",
            "search-service", "granite-service", "aem-service", "admin", "anonymous"
    );

    @Override
    public String getRuleId() {
        return RuleId.AEM_CLOUD_003.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();

        // 1. Extract OSGi Service User Mappings
        Set<String> mappedSubservices = new HashSet<>();
        Set<String> defaultMappedBundles = new HashSet<>();
        Map<String, Set<String>> mappingsByBundle = new HashMap<>();
        Map<String, String> subserviceToUser = new HashMap<>();
        Map<String, OSGiConfigNode> subserviceToConfig = new HashMap<>();
        boolean anyMapperConfig = false;

        for (OSGiConfigNode config : context.configs()) {
            if (config.getPid() != null && config.getPid().startsWith(SERVICE_USER_MAPPER_PID_PREFIX)) {
                anyMapperConfig = true;
                ConfigPropertyNode prop = config.getProperty("user.mapping");
                if (prop != null && prop.getRawValue() != null) {
                    parseMappingEntries(prop.getRawValue(), config, mappedSubservices, defaultMappedBundles,
                            mappingsByBundle, subserviceToUser, subserviceToConfig);
                }
            }
        }

        // 2. Discover declared system users across all project sources generically
        Set<String> declaredUsers = discoverDeclaredSystemUsers(context);

        // 3. Cross-reference service-resolver acquisitions against mappedSubservices
        for (SubserviceCall call : context.subserviceCalls()) {
            if (call.resolution() == SubserviceCall.Resolution.UNRESOLVED) {
                // A variable/constant we could not evaluate is not the same as an explicit null.
                // Fail open instead of guessing a subservice name.
                continue;
            }
            String subservice = call.subserviceName();
            String callerBundle = context.bundleSymbolicNameFor(call.filePath()).orElse(null);
            if (subservice == null || subservice.isBlank()) {
                if (callerBundle != null && defaultMappedBundles.contains(callerBundle)) {
                    continue;
                }
                // A resolver acquisition without an explicit subservice resolves through the calling
                // bundle's default (bundle-level) user mapping. Report only when the project has no mapper
                // configuration at all: when mappings exist, the bundle may be covered by an
                // external or platform mapping we cannot see, so fail open.
                if (!anyMapperConfig && defaultMappedBundles.isEmpty()) {
                    violations.add(new Violation(
                            RuleId.AEM_CLOUD_003.id(),
                            RuleId.AEM_CLOUD_003.defaultLevel(),
                            "UNMAPPED_DEFAULT_SUBSERVICE",
                            "Java service-resolver acquisition without an explicit subservice uses the bundle's default "
                                    + "subservice, but no bundle-level 'user.mapping' entry exists in any "
                                    + "ServiceUserMapperImpl.amended configuration.",
                            relativePath(context, call.filePath()),
                            new TextRegion(call.line(), call.column(), call.line(), call.column() + 26),
                            null,
                            "Add a bundle-level mapping '<bundle-symbolic-name>=[<service-user-name>]' to "
                                    + "'org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended' "
                                    + "or pass an explicit subservice name and map it.",
                            List.of()
                    ));
                }
                continue;
            }
            boolean mapped = mappedSubservices.contains(subservice);
            if (callerBundle != null && mappingsByBundle.containsKey(callerBundle)) {
                mapped = mappingsByBundle.get(callerBundle).contains(subservice);
            }
            if (!mapped) {
                if (isUnresolvedConstant(subservice)) {
                    // The extractor could not resolve a cross-file constant to its literal value.
                    // Comparing a Java symbol such as SOME_SERVICE_SUBSERVICE_NAME with mapping values
                    // would be a guaranteed false positive, so fail open.
                    continue;
                }
                violations.add(new Violation(
                        RuleId.AEM_CLOUD_003.id(),
                        RuleId.AEM_CLOUD_003.defaultLevel(),
                        "UNMAPPED_SUBSERVICE_USER",
                        "Java service-resolver acquisition with subservice '" + subservice
                                + "' has no matching 'user.mapping' in any ServiceUserMapperImpl.amended configuration.",
                        relativePath(context, call.filePath()),
                        new TextRegion(call.line(), call.column(), call.line(), call.column() + 26),
                        null,
                        "Add an OSGi configuration for 'org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended' with 'user.mapping': ['<bundle-id>:" + subservice + "=[<service-user-name>]'].",
                        List.of()
                ));
            }
        }

        // 4. Cross-reference mapped users against declared users
        for (Map.Entry<String, String> entry : subserviceToUser.entrySet()) {
            String subservice = entry.getKey();
            String user = entry.getValue();
            if (user != null && !user.isBlank() && !declaredUsers.contains(user)) {
                OSGiConfigNode config = subserviceToConfig.get(subservice);
                Path cfgPath = config != null ? config.getFilePath() : null;
                violations.add(new Violation(
                        RuleId.AEM_CLOUD_003.id(),
                        Severity.WARNING,
                        "UNRESOLVED_SERVICE_USER",
                        "Service user '" + user + "' mapped for subservice '" + subservice
                                + "' in ServiceUserMapperImpl.amended is not declared in any repoinit script, JCR rep:SystemUser package, or ACL configuration.",
                        relativePath(context, cfgPath),
                        TextRegion.forWholeFile(),
                        config != null ? config.getPid() : null,
                        "Declare the system user via repoinit ('create service user " + user + "') or JCR rep:SystemUser package before deployment.",
                        List.of()
                ));
            }
        }

        return violations;
    }

    private boolean isUnresolvedConstant(String subservice) {
        // Java constant names are upper snake case. Literal Sling subservices are normally lower
        // case, dot- or hyphen-separated. Only treat a wholly upper-case token as unresolved so a
        // genuinely odd literal is still checked.
        return subservice != null
                && subservice.matches("[A-Z][A-Z0-9_]{2,}")
                && (subservice.contains("_") || subservice.length() > 6);
    }

    private void parseMappingEntries(String rawValue, OSGiConfigNode config,
                                     Set<String> mappedSubservices,
                                     Set<String> defaultMappedBundles,
                                     Map<String, Set<String>> mappingsByBundle,
                                     Map<String, String> subserviceToUser,
                                     Map<String, OSGiConfigNode> subserviceToConfig) {
        Matcher matcher = MAPPING_ENTRY_PATTERN.matcher(rawValue);
        while (matcher.find()) {
            String bundleId = matcher.group(1);
            String subservice = matcher.group(2);
            String user = matcher.group(3);
            if (subservice != null && !subservice.isBlank()) {
                mappedSubservices.add(subservice);
                if (bundleId != null && !bundleId.isBlank()) {
                    mappingsByBundle.computeIfAbsent(bundleId, key -> new HashSet<>()).add(subservice);
                }
                if (user != null && !user.isBlank()) {
                    subserviceToUser.put(subservice, user);
                    subserviceToConfig.put(subservice, config);
                }
            } else if (bundleId != null && !bundleId.isBlank()) {
                // Bundle-level mapping: "com.example.bundle=[user]" (no ':' subservice).
                defaultMappedBundles.add(bundleId);
            }
        }
    }

    private Set<String> discoverDeclaredSystemUsers(RuleContext context) {
        Set<String> users = new HashSet<>(OOTB_SYSTEM_USERS);

        // A. From ALL OSGi configurations in the project (RepositoryInitializer or any other)
        for (OSGiConfigNode config : context.configs()) {
            for (ConfigPropertyNode prop : config.getProperties().values()) {
                if (prop.getRawValue() != null) {
                    Matcher m = REPOINIT_USER_PATTERN.matcher(prop.getRawValue());
                    while (m.find()) {
                        users.add(m.group(1));
                    }
                }
            }
        }

        // B. From standalone files across the entire project (YAML, XML, repoinit, text)
        Path root = context.projectRoot();
        if (root == null || !Files.isDirectory(root)) {
            return users;
        }

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return !s.contains("/target/")
                                && !s.contains("/node_modules/")
                                && !s.contains("/.git/")
                                && !s.contains("/.idea/")
                                && !s.contains("/.vscode/");
                    })
                    .forEach(file -> scanFileForSystemUsers(file, users));
        } catch (IOException ignored) {
        }

        return users;
    }

    private void scanFileForSystemUsers(Path file, Set<String> users) {
        String fileName = file.getFileName().toString().toLowerCase();
        String pathStr = file.toString().replace('\\', '/');

        // 1. Filesystem path inspection for JCR system user nodes
        scanPathForSystemUsers(pathStr, users);

        // 2. Content inspection by file type
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            scanYamlForSystemUsers(file, users);
        } else if (fileName.endsWith(".xml")) {
            scanXmlForSystemUsers(file, users);
        } else {
            scanGenericTextForSystemUsers(file, users);
        }
    }

    private void scanPathForSystemUsers(String pathStr, Set<String> users) {
        int idx = pathStr.indexOf("/home/users/system/");
        if (idx >= 0) {
            String sub = pathStr.substring(idx + "/home/users/system/".length());
            String[] segments = sub.split("/");
            for (String segment : segments) {
                if (!segment.isEmpty() && !segment.contains(".") && !segment.contains(":")
                        && !segment.equals("_cq_") && !segment.equals("rep:policy")) {
                    users.add(segment);
                }
            }
        }
    }

    private void scanYamlForSystemUsers(Path file, Set<String> users) {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            boolean inServiceUsersList = false;
            int listIndent = 0;
            String currentKey = null;
            String currentName = null;
            boolean blockHasSystemIndicator = false;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                // Direct repoinit statements embedded in YAML
                Matcher repoinitMatcher = REPOINIT_USER_PATTERN.matcher(trimmed);
                while (repoinitMatcher.find()) {
                    users.add(repoinitMatcher.group(1));
                }

                // Explicit service_user: <name>
                Matcher explicitMatcher = YAML_EXPLICIT_SERVICE_USER.matcher(trimmed);
                if (explicitMatcher.find()) {
                    users.add(explicitMatcher.group(1));
                }

                // service_users: or system_users: list header
                if (YAML_SERVICE_USERS_SECTION.matcher(trimmed).matches()) {
                    inServiceUsersList = true;
                    listIndent = getIndent(line);
                    continue;
                }

                if (inServiceUsersList) {
                    int indent = getIndent(line);
                    if (indent > listIndent && trimmed.startsWith("-")) {
                        Matcher m = YAML_LIST_ITEM.matcher(trimmed);
                        if (m.find()) {
                            users.add(m.group(1));
                        }
                        continue;
                    } else if (indent <= listIndent) {
                        inServiceUsersList = false;
                    }
                }

                // Check for new YAML list item entry key (e.g. a system-user block):
                Matcher keyMatcher = YAML_KEY_PATTERN.matcher(line);
                if (keyMatcher.find()) {
                    String matchedKey = keyMatcher.group(1);
                    if (!YAML_RESERVED_KEYS.contains(matchedKey.toLowerCase())) {
                        if (blockHasSystemIndicator && currentKey != null) {
                            users.add(currentKey);
                        }
                        currentKey = matchedKey;
                        currentName = null;
                        blockHasSystemIndicator = false;
                        continue;
                    }
                }

                // Check indicators within block
                if (YAML_IS_SYSTEM_USER.matcher(trimmed).find()) {
                    blockHasSystemIndicator = true;
                    if (currentKey != null && !YAML_RESERVED_KEYS.contains(currentKey.toLowerCase())) {
                        users.add(currentKey);
                    }
                }

                if (trimmed.contains("/home/users/system")) {
                    blockHasSystemIndicator = true;
                    if (currentKey != null && !YAML_RESERVED_KEYS.contains(currentKey.toLowerCase())) {
                        users.add(currentKey);
                    }
                    Matcher pathMatcher = YAML_PATH_PATTERN.matcher(trimmed);
                    if (pathMatcher.find()) {
                        String lastSegment = pathMatcher.group(1);
                        if (lastSegment != null && !lastSegment.isBlank()) {
                            users.add(lastSegment);
                        }
                    }
                }

                Matcher nameMatcher = YAML_NAME_PROPERTY.matcher(trimmed);
                if (nameMatcher.find()) {
                    currentName = nameMatcher.group(1);
                    if (blockHasSystemIndicator && !YAML_RESERVED_KEYS.contains(currentName.toLowerCase())) {
                        users.add(currentName);
                    }
                }
            }

            // Flush trailing block
            if (blockHasSystemIndicator) {
                if (currentKey != null && !YAML_RESERVED_KEYS.contains(currentKey.toLowerCase())) {
                    users.add(currentKey);
                }
                if (currentName != null && !YAML_RESERVED_KEYS.contains(currentName.toLowerCase())) {
                    users.add(currentName);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void scanXmlForSystemUsers(Path file, Set<String> users) {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            boolean hasSystemUser = false;
            while ((line = reader.readLine()) != null) {
                if (line.contains("rep:SystemUser")) {
                    hasSystemUser = true;

                    Matcher tagMatcher = JCR_SYSTEM_USER_TAG.matcher(line);
                    if (tagMatcher.find()) {
                        String tagName = tagMatcher.group(1);
                        if (!tagName.equals("jcr:root") && !tagName.equals("rep:SystemUser")
                                && !tagName.contains(":")) {
                            users.add(tagName);
                        }
                    }

                    Matcher idMatcher = JCR_AUTHORIZABLE_ID.matcher(line);
                    if (idMatcher.find()) {
                        users.add(idMatcher.group(1));
                    }

                    Matcher principalMatcher = JCR_PRINCIPAL_NAME.matcher(line);
                    if (principalMatcher.find()) {
                        users.add(principalMatcher.group(1));
                    }
                }
            }

            if (hasSystemUser) {
                Path parent = file.getParent();
                if (parent != null) {
                    String folderName = parent.getFileName().toString();
                    if (!folderName.equals("system") && !folderName.equals("users")
                            && !folderName.equals("home") && !folderName.equals("jcr_root")
                            && !folderName.contains(".")) {
                        users.add(folderName);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void scanGenericTextForSystemUsers(Path file, Set<String> users) {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = REPOINIT_USER_PATTERN.matcher(line);
                while (m.find()) {
                    users.add(m.group(1));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private int getIndent(String line) {
        int indent = 0;
        while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
            indent++;
        }
        return indent;
    }

    private String relativePath(RuleContext context, Path path) {
        return context.relativePath(path);
    }
}
