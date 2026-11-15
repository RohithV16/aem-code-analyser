package io.github.rohithv16.aem.analyser.core.export;

/**
 * Raised when report export fails.
 */
public class ExportException extends Exception {

    public ExportException(String message) {
        super(message);
    }

    public ExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
