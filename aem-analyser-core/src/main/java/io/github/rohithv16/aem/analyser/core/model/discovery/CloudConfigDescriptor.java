package io.github.rohithv16.aem.analyser.core.model.discovery;

import java.nio.file.Path;

/**
 * A non-Maven Cloud Manager configuration descriptor (cdn.yaml, WAF, rate limiting).
 *
 * @param filePath     absolute file path
 * @param relativePath path relative to the repository root
 * @param type         descriptor type (cdn, waf, rate-limit, request-routing)
 * @param environment  environment tier (dev, stage, prod, rde) or GLOBAL
 */
public record CloudConfigDescriptor(Path filePath, String relativePath, String type, String environment) {
}
