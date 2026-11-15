package io.github.rohithv16.aem.analyser.core.export;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Resolves the analyser version for report metadata.
 *
 * <p>Priority: the filtered {@code version.properties} resource (always present in a Maven build),
 * then the JAR manifest {@code Implementation-Version}, then {@code "dev"} for an IDE run.
 */
public final class VersionInfo {

    private static final String VERSION = load();

    private VersionInfo() {
    }

    public static String current() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = VersionInfo.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String value = properties.getProperty("version");
                if (value != null && !value.isBlank() && !value.contains("${")) {
                    return value.trim();
                }
            }
        } catch (IOException ignored) {
            // fall through to manifest
        }
        String manifest = VersionInfo.class.getPackage().getImplementationVersion();
        return manifest != null && !manifest.isBlank() ? manifest : "dev";
    }
}
