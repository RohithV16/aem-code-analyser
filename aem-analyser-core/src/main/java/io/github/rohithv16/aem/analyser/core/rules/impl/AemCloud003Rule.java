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
 * Cross-references Java getServiceResourceResolver() calls with OSGi ServiceUserMapperImpl.amended
 * configurations and system user declarations across repoinit, JCR content packages, and ACL YAMLs.
 */
public final class AemCloud003Rule implements RuleEvaluator {

    private static final String SERVICE_USER_MAPPER_PID_PREFIX =
            "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended";

    private static final String REPOINIT_PID_PREFIX =
            "org.apache.sling.jcr.repoinit.RepositoryInitializer";

    private static final Pattern MAPPING_ENTRY_PATTERN = Pattern.compile(
            "([a-zA-Z0-9_.-]+)(?::([a-zA-Z0-9_.-]+))?=\\[?([a-zA-Z0-9_.-]+)\\]?"
    );

    private static final Pattern REPOINIT_USER_PATTERN = Pattern.compile(
            "(?i)create\\s+(?:service\\s+)?user\\s+([a-zA-Z0-9_-]+)"
    );

    private static final Pattern JCR_SYSTEM_USER_PATTERN = Pattern.compile(
            "<([a-zA-Z0-9_-]+)[^>]*jcr:primaryType=[\"']rep:SystemUser[\"']"
    );

    private static final Pattern ACL_USER_PATTERN = Pattern.compile(
            "^\\s*-\\s*([a-zA-Z0-9_-]+):"
    );

    private static final Set<String> OOTB_SYSTEM_USERS = Set.of(
            "repository-service", "workflow-service", "data-service",
            "content-writer-service", "replication-service", "asset-writer-service",
            "version-manager-service", "tag-garbage-collector", "fd-service",
            "screens-service", "commerce-service", "oauth-service", "dam-update-service"
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
        Map<String, String> subserviceToUser = new HashMap<>();
        Map<String, OSGiConfigNode> subserviceToConfig = new HashMap<>();

        for (OSGiConfigNode config : context.configs()) {
            if (config.getPid() != null && config.getPid().startsWith(SERVICE_USER_MAPPER_PID_PREFIX)) {
                ConfigPropertyNode prop = config.getProperty("user.mapping");
                if (prop != null && prop.getRawValue() != null) {
                    parseMappingEntries(prop.getRawValue(), config, mappedSubservices, subserviceToUser, subserviceToConfig);
                }
            }
        }

        // 2. Discover declared system users across repoinit, JCR content packages, and ACLs
        Set<String> declaredUsers = discoverDeclaredSystemUsers(context);

        // 3. Cross-reference Java getServiceResourceResolver calls against mappedSubservices
        for (SubserviceCall call : context.subserviceCalls()) {
            String subservice = call.subserviceName();
            if (subservice != null && !subservice.isBlank() && !mappedSubservices.contains(subservice)) {
                violations.add(new Violation(
                        RuleId.AEM_CLOUD_003.id(),
                        RuleId.AEM_CLOUD_003.defaultLevel(),
                        "UNMAPPED_SUBSERVICE_USER",
                        "Java call to getServiceResourceResolver() with subservice '" + subservice
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

    private void parseMappingEntries(String rawValue, OSGiConfigNode config,
                                    Set<String> mappedSubservices,
                                    Map<String, String> subserviceToUser,
                                    Map<String, OSGiConfigNode> subserviceToConfig) {
        Matcher matcher = MAPPING_ENTRY_PATTERN.matcher(rawValue);
        while (matcher.find()) {
            String subservice = matcher.group(2);
            String user = matcher.group(3);
            if (subservice != null && !subservice.isBlank()) {
                mappedSubservices.add(subservice);
                if (user != null && !user.isBlank()) {
                    subserviceToUser.put(subservice, user);
                    subserviceToConfig.put(subservice, config);
                }
            }
        }
    }

    private Set<String> discoverDeclaredSystemUsers(RuleContext context) {
        Set<String> users = new HashSet<>(OOTB_SYSTEM_USERS);

        // A. From RepositoryInitializer OSGi configs
        for (OSGiConfigNode config : context.configs()) {
            if (config.getPid() != null && config.getPid().startsWith(REPOINIT_PID_PREFIX)) {
                ConfigPropertyNode scriptsProp = config.getProperty("scripts");
                if (scriptsProp != null && scriptsProp.getRawValue() != null) {
                    Matcher m = REPOINIT_USER_PATTERN.matcher(scriptsProp.getRawValue());
                    while (m.find()) {
                        users.add(m.group(1));
                    }
                }
            }
        }

        // B. From standalone repoinit files, JCR package .content.xml, and ACL YAMLs
        Path root = context.projectRoot();
        if (root == null || !Files.isDirectory(root)) {
            return users;
        }

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return !s.contains("/target/") && !s.contains("/node_modules/") && !s.contains("/.git/");
                    })
                    .forEach(file -> scanFileForSystemUsers(file, users));
        } catch (IOException ignored) {
        }

        return users;
    }

    private void scanFileForSystemUsers(Path file, Set<String> users) {
        String fileName = file.getFileName().toString();
        String pathStr = file.toString().replace('\\', '/');

        // Path ending in /home/users/system/<username>
        if (pathStr.contains("/home/users/system/")) {
            int idx = pathStr.indexOf("/home/users/system/");
            String sub = pathStr.substring(idx + "/home/users/system/".length());
            int slash = sub.indexOf('/');
            String candidate = slash > 0 ? sub.substring(0, slash) : sub;
            if (!candidate.isBlank() && !candidate.contains(".")) {
                users.add(candidate);
            }
        }

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Repoinit file
                if (fileName.endsWith(".repoinit") || fileName.endsWith(".txt")) {
                    Matcher m = REPOINIT_USER_PATTERN.matcher(line);
                    while (m.find()) {
                        users.add(m.group(1));
                    }
                }
                // .content.xml
                if (fileName.endsWith(".content.xml") && line.contains("rep:SystemUser")) {
                    Matcher m = JCR_SYSTEM_USER_PATTERN.matcher(line);
                    while (m.find()) {
                        users.add(m.group(1));
                    }
                }
                // YAML ACLTool
                if ((fileName.endsWith(".yaml") || fileName.endsWith(".yml")) && pathStr.contains("/acl/")) {
                    Matcher m = ACL_USER_PATTERN.matcher(line);
                    if (m.find()) {
                        users.add(m.group(1));
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private String relativePath(RuleContext context, Path path) {
        if (path == null) {
            return "";
        }
        return context.projectRoot().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
