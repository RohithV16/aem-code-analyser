package io.github.rohithv16.aem.analyser.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/**
 * Provides the CLI version from the Maven build metadata on the classpath.
 */
public class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/aem-analyser.properties")) {
            String version = "1.0.0";
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                version = props.getProperty("version", version);
            }
            return new String[]{"aem-analyser " + version};
        }
    }
}
