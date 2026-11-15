package io.github.rohithv16.aem.analyser.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@code .aem-analyser.yml} with strict unknown-field rejection.
 */
public final class AnalyserConfigLoader {

    public static final String DEFAULT_FILE = ".aem-analyser.yml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private AnalyserConfigLoader() {
    }

    public static AnalyserConfig load(Path file) throws IOException {
        return YAML.readValue(Files.newBufferedReader(file), AnalyserConfig.class);
    }

    public static AnalyserConfig loadIfPresent(Path projectRoot) throws IOException {
        Path defaultFile = projectRoot.resolve(DEFAULT_FILE);
        return Files.isRegularFile(defaultFile) ? load(defaultFile) : AnalyserConfig.empty();
    }
}
