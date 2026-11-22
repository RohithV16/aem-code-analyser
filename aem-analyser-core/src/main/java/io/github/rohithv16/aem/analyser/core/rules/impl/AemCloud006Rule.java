package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RelatedLocation;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.parser.dispatcher.DispatcherConfigParser;
import io.github.rohithv16.aem.analyser.core.parser.dispatcher.DispatcherConfigParser.DispatcherConfig;
import io.github.rohithv16.aem.analyser.core.parser.dispatcher.DispatcherConfigParser.IncludeDirective;
import io.github.rohithv16.aem.analyser.core.parser.dispatcher.DispatcherConfigParser.VirtualHost;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AEM-CLOUD-006: broken dispatcher configuration.
 *
 * <p>AEM as a Cloud Service validates the dispatcher only when the Cloud Manager pipeline deploys,
 * so every defect here costs a full build to discover. The rule reports the four shapes that fail
 * that deployment: an include that resolves to no file, two virtual hosts claiming one
 * {@code ServerName}, a virtual host without a {@code DocumentRoot}, and a legacy monolithic
 * {@code dispatcher.any} outside {@code conf.dispatcher.d}.
 *
 * <p>Configuration that only exists at runtime is deliberately not judged: the stock SDK
 * {@code Include conf.d/enabled_vhosts/*.vhost} matches nothing in source control because the
 * enabled set is built from symlinks at deploy time.
 */
public final class AemCloud006Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_CLOUD_006.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        Path root = context.projectRoot();
        if (root == null) {
            return List.of();
        }
        DispatcherConfig config = new DispatcherConfigParser().parse(root);
        if (config.moduleRoots().isEmpty()) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        unresolvedIncludes(context, config, violations);
        duplicateServerNames(context, config, violations);
        missingDocumentRoots(context, config, violations);
        legacyDispatcherAny(context, config, violations);
        return violations;
    }

    private void unresolvedIncludes(RuleContext context, DispatcherConfig config,
                                    List<Violation> violations) {
        for (IncludeDirective include : config.includes()) {
            if (include.resolved()) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_CLOUD_006.id(),
                    RuleId.AEM_CLOUD_006.defaultLevel(),
                    "UNRESOLVED_DISPATCHER_INCLUDE",
                    "Dispatcher " + include.directive() + " '" + include.target()
                            + "' resolves to no file in the dispatcher module.",
                    context.relativePath(include.file()),
                    TextRegion.forLine(include.line()),
                    null,
                    "Create the included file under the dispatcher module (conf.d or conf.dispatcher.d) "
                            + "or correct the path in the " + include.directive() + " directive.",
                    List.of()));
        }
    }

    private void duplicateServerNames(RuleContext context, DispatcherConfig config,
                                      List<Violation> violations) {
        Map<String, VirtualHost> firstByName = new LinkedHashMap<>();
        for (VirtualHost vhost : config.virtualHosts()) {
            if (vhost.serverName() == null || vhost.serverName().isBlank()) {
                continue;
            }
            String key = vhost.serverName().toLowerCase(Locale.ROOT);
            VirtualHost first = firstByName.putIfAbsent(key, vhost);
            if (first == null) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_CLOUD_006.id(),
                    RuleId.AEM_CLOUD_006.defaultLevel(),
                    "DUPLICATE_SERVER_NAME",
                    "ServerName '" + vhost.serverName() + "' is already claimed by "
                            + context.relativePath(first.file()) + ":" + first.line()
                            + "; only one virtual host can answer for a host name.",
                    context.relativePath(vhost.file()),
                    TextRegion.forLine(vhost.line()),
                    null,
                    "Give each virtual host a distinct ServerName, or delete the duplicate host.",
                    List.of(new RelatedLocation(
                            "First virtual host claiming this ServerName",
                            context.relativePath(first.file()),
                            TextRegion.forLine(first.line())))));
        }
    }

    private void missingDocumentRoots(RuleContext context, DispatcherConfig config,
                                      List<Violation> violations) {
        for (VirtualHost vhost : config.virtualHosts()) {
            if (vhost.hasDocumentRoot()) {
                continue;
            }
            String name = vhost.serverName() == null || vhost.serverName().isBlank()
                    ? "(no ServerName)"
                    : vhost.serverName();
            violations.add(new Violation(
                    RuleId.AEM_CLOUD_006.id(),
                    RuleId.AEM_CLOUD_006.defaultLevel(),
                    "MISSING_DOCUMENT_ROOT",
                    "VirtualHost " + name + " declares no DocumentRoot, so the dispatcher cannot map "
                            + "requests to its cache root.",
                    context.relativePath(vhost.file()),
                    TextRegion.forLine(vhost.line()),
                    null,
                    "Add DocumentRoot \"${DOCROOT}\" (or the farm's docroot) to the virtual host.",
                    List.of()));
        }
    }

    private void legacyDispatcherAny(RuleContext context, DispatcherConfig config,
                                     List<Violation> violations) {
        for (Path file : config.monolithicDispatcherAny()) {
            violations.add(new Violation(
                    RuleId.AEM_CLOUD_006.id(),
                    RuleId.AEM_CLOUD_006.defaultLevel(),
                    "LEGACY_DISPATCHER_ANY",
                    "Monolithic dispatcher.any found outside conf.dispatcher.d; the Cloud layout "
                            + "requires config split into conf.dispatcher.d and conf.d.",
                    context.relativePath(file),
                    TextRegion.forWholeFile(),
                    null,
                    "Move the configuration into conf.dispatcher.d (farms, filters, cache, renders, "
                            + "virtualhosts) and conf.d (vhosts, rewrites, variables).",
                    List.of()));
        }
    }
}
