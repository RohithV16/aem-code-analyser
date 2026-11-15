package io.github.aemtools.analyser.core.api;

/**
 * Raised when the analysis cannot be executed to completion.
 */
public class AnalysisException extends Exception {

    public AnalysisException(String message) {
        super(message);
    }

    public AnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
