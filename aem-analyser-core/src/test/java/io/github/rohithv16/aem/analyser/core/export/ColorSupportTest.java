package io.github.rohithv16.aem.analyser.core.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorSupportTest {

    @Test
    void colourIsOffWhenStdoutIsNotATerminal() {
        assertFalse(ColorSupport.enabled(false, null, "xterm-256color"),
                "a redirected stream must never receive ANSI escapes");
    }

    @Test
    void colourIsOnForAnInteractiveTerminal() {
        assertTrue(ColorSupport.enabled(true, null, "xterm-256color"));
    }

    @Test
    void noColorDisablesColourEvenOnATerminal() {
        assertFalse(ColorSupport.enabled(true, "1", "xterm-256color"));
        assertFalse(ColorSupport.enabled(true, "true", "xterm-256color"));
    }

    /** The NO_COLOR convention is "set and non-empty"; an empty value means unset. */
    @Test
    void emptyNoColorIsTreatedAsUnset() {
        assertTrue(ColorSupport.enabled(true, "", "xterm-256color"));
    }

    @Test
    void dumbTerminalGetsNoColour() {
        assertFalse(ColorSupport.enabled(true, null, "dumb"));
        assertFalse(ColorSupport.enabled(true, null, "DUMB"));
    }
}
