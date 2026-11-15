package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.htl.CqComponentDefinition;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discovers cq:Component definitions and flags legacy Classic UI dialogs.
 */
public final class JcrComponentScanner {

    private static final Pattern RESOURCE_SUPER_TYPE_PATTERN = Pattern.compile(
            "sling:resourceSuperType=\"([^\"]+)\""
    );
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "jcr:title=\"([^\"]+)\""
    );
    private static final Pattern COMPONENT_GROUP_PATTERN = Pattern.compile(
            "componentGroup=\"([^\"]+)\""
    );

    public List<CqComponentDefinition> scanDirectory(Path directory) {
        List<CqComponentDefinition> components = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return components;
        }

        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(".content.xml"))
                    .filter(this::isNotIgnored)
                    .forEach(xmlFile -> scanContentXml(xmlFile, components));
        } catch (IOException ignored) {
        }

        return components;
    }

    private void scanContentXml(Path xmlFile, List<CqComponentDefinition> collector) {
        try {
            String content = Files.readString(xmlFile);
            if (!content.contains("jcr:primaryType=\"cq:Component\"")) {
                return;
            }

            Path compDir = xmlFile.getParent();
            String resType = deriveResourceType(compDir);
            String superType = extractAttribute(content, RESOURCE_SUPER_TYPE_PATTERN);
            String title = extractAttribute(content, TITLE_PATTERN);
            String group = extractAttribute(content, COMPONENT_GROUP_PATTERN);

            Path classicDialog = compDir.resolve("dialog.xml");
            boolean hasClassic = Files.isRegularFile(classicDialog);

            collector.add(new CqComponentDefinition(
                    compDir, xmlFile, resType, superType, title, group, hasClassic, hasClassic ? classicDialog : null
            ));
        } catch (IOException ignored) {
        }
    }

    private String deriveResourceType(Path compDir) {
        String pathStr = compDir.toAbsolutePath().normalize().toString().replace('\\', '/');
        int appsIdx = pathStr.indexOf("/jcr_root/apps/");
        if (appsIdx != -1) {
            return pathStr.substring(appsIdx + "/jcr_root/apps/".length());
        }
        int libsIdx = pathStr.indexOf("/jcr_root/libs/");
        if (libsIdx != -1) {
            return pathStr.substring(libsIdx + "/jcr_root/libs/".length());
        }
        return compDir.getFileName().toString();
    }

    private String extractAttribute(String content, Pattern pattern) {
        Matcher m = pattern.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private boolean isNotIgnored(Path path) {
        String s = path.toString();
        return !s.contains("/target/") && !s.contains("/node_modules/") && !s.contains("/.git/");
    }
}
