package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.discovery.ModuleDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectDiscoveryEngineTest {

    @TempDir
    Path root;

    private void write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void reactor() throws Exception {
        write("pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>child-b</module>
                  </modules>
                  <profiles>
                    <profile>
                      <id>default-extras</id>
                      <activation><activeByDefault>true</activeByDefault></activation>
                      <modules><module>child-a</module></modules>
                    </profile>
                    <profile>
                      <id>other</id>
                    </profile>
                  </profiles>
                </project>
                """);
        write("child-a/pom.xml", childPom("child-a"));
        write("child-b/pom.xml", childPom("child-b"));
    }

    private String childPom(String artifactId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>%s</artifactId>
                  <packaging>jar</packaging>
                </project>
                """.formatted(artifactId);
    }

    private Set<String> discoveredArtifacts(List<String> profiles) throws Exception {
        try (ProjectDiscoveryEngine engine = new ProjectDiscoveryEngine()) {
            return engine.discover(root, profiles).modules().stream()
                    .map(ModuleDescriptor::artifactId)
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void activeByDefaultProfileModuleIsIncludedWhenNoProfileIsRequested() throws Exception {
        reactor();

        Set<String> artifacts = discoveredArtifacts(List.of());

        assertTrue(artifacts.containsAll(Set.of("root", "child-a", "child-b")), artifacts::toString);
    }

    @Test
    void explicitlyActivatingAnotherProfileDisablesActiveByDefault() throws Exception {
        reactor();

        Set<String> artifacts = discoveredArtifacts(List.of("other"));

        assertTrue(artifacts.containsAll(Set.of("root", "child-b")), artifacts::toString);
        assertFalse(artifacts.contains("child-a"), () -> "activeByDefault module must be disabled: " + artifacts);
    }
}
