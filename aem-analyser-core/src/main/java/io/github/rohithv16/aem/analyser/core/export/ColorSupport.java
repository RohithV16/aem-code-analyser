package io.github.rohithv16.aem.analyser.core.export;

/**
 * Decides whether ANSI colour may be written to standard output.
 *
 * <p>Colour is a presentation nicety that corrupts machine consumption: a redirected console report
 * full of escape sequences breaks CI log viewers, PR comments and anything that parses the output.
 * The default therefore has to be "colour only on a terminal", with {@code NO_COLOR} and
 * {@code --no-color} as explicit opt-outs.
 */
public final class ColorSupport {

    /** The {@code NO_COLOR} convention: any non-empty value disables colour. */
    public static final String NO_COLOR = "NO_COLOR";

    private ColorSupport() {
    }

    /**
     * True when standard output is an interactive terminal.
     *
     * <p>{@link System#console()} is non-null only while the JVM owns a terminal on both standard
     * streams, so a redirect to a file or a pipe reports {@code false}. It fires once per process,
     * before any writer is consulted, which is why the result is passed in rather than re-read.
     */
    public static boolean stdoutIsTerminal() {
        return System.console() != null;
    }

    /**
     * @param stdoutIsTerminal whether standard output is a TTY
     * @param noColorValue     the raw {@code NO_COLOR} environment value, or {@code null}
     * @param termValue        the raw {@code TERM} environment value, or {@code null}
     * @return whether ANSI colour should be emitted
     */
    public static boolean enabled(boolean stdoutIsTerminal, String noColorValue, String termValue) {
        if (noColorValue != null && !noColorValue.isEmpty()) {
            return false;
        }
        if (termValue != null && "dumb".equalsIgnoreCase(termValue.strip())) {
            return false;
        }
        return stdoutIsTerminal;
    }

    /** Applies {@link #enabled(boolean, String, String)} to this process's environment. */
    public static boolean enabled(boolean stdoutIsTerminal) {
        return enabled(stdoutIsTerminal,
                System.getenv(NO_COLOR),
                System.getenv("TERM"));
    }
}
