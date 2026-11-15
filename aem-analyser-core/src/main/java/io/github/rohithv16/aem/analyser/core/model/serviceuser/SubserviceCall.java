package io.github.rohithv16.aem.analyser.core.model.serviceuser;

import java.nio.file.Path;

/**
 * Represents a call to getServiceResourceResolver(...) or loginService(...).
 */
public record SubserviceCall(
        String subserviceName,
        String callingClassFqn,
        Path filePath,
        int line,
        int column
) {}
