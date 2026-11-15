package io.github.rohithv16.aem.analyser.core.model.serviceuser;

import java.nio.file.Path;

/**
 * Represents a call to getServiceResourceResolver(...) or loginService(...).
 *
 * <p>The resolution state matters: a genuinely {@code null} subservice uses the bundle's default
 * mapping, while an {@code UNRESOLVED} argument (a variable or cross-file constant we could not
 * evaluate) must not be treated as {@code null} — that would create false positives.
 */
public record SubserviceCall(
        String subserviceName,
        String callingClassFqn,
        Path filePath,
        int line,
        int column,
        Resolution resolution
) {

    public enum Resolution {
        /** A literal subservice value was resolved. */
        LITERAL,
        /** The call explicitly passes {@code null}. */
        NULL,
        /** The argument is a variable/constant whose value could not be determined. */
        UNRESOLVED
    }

    public SubserviceCall(String subserviceName, String callingClassFqn, Path filePath, int line, int column) {
        this(subserviceName, callingClassFqn, filePath, line, column,
                subserviceName == null ? Resolution.NULL : Resolution.LITERAL);
    }

    public boolean isResolved() {
        return resolution == Resolution.LITERAL;
    }

    public boolean isExplicitNull() {
        return resolution == Resolution.NULL;
    }
}
