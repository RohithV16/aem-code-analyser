package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.htl.CqComponentDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers cq:Component definitions and flags legacy Classic UI dialogs.
 */
public final class JcrComponentScanner {

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

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
        return scanDirectory(directory, new io.github.rohithv16.aem.analyser.core.util.DiagnosticSink());
    }

    public List<CqComponentDefinition> scanDirectory(Path directory,
                                                     io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics) {
        return scanDirectory(directory, diagnostics, MAX_FILE_SIZE_BYTES);
    }

    public List<CqComponentDefinition> scanDirectory(Path directory,
                                                     io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics,
                                                     long maxFileSizeBytes) {
        return scanDirectory(FileInventory.scan(directory), directory, diagnostics, maxFileSizeBytes);
    }

    /** Scans every {@code .content.xml} below {@code directory} from the run's shared inventory. */
    public List<CqComponentDefinition> scanDirectory(FileInventory inventory, Path directory,
                                                     io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics,
                                                     long maxFileSizeBytes) {
        List<CqComponentDefinition> components = new ArrayList<>();
        if (inventory == null || directory == null || !Files.isDirectory(directory)) {
            return components;
        }

        for (Path xmlFile : inventory.filesUnder(directory,
                file -> file.getFileName().toString().equals(".content.xml"))) {
            if (isNotIgnored(xmlFile)) {
                scanContentXml(xmlFile, components, diagnostics, maxFileSizeBytes);
            }
        }

        return components;
    }

    private void scanContentXml(Path xmlFile, List<CqComponentDefinition> collector,
                                io.github.rohithv16.aem.analyser.core.util.DiagnosticSink diagnostics,
                                long maxFileSizeBytes) {
        try {
            if (Files.size(xmlFile) > maxFileSizeBytes) {
                if (diagnostics != null) {
                    diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.FILE_TOO_LARGE,
                            xmlFile, "exceeds " + (maxFileSizeBytes / (1024 * 1024)) + " MiB");
                }
                return;
            }
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
        } catch (IOException e) {
            if (diagnostics != null) {
                diagnostics.add(io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind.UNREADABLE_TREE,
                        xmlFile, io.github.rohithv16.aem.analyser.core.util.DiagnosticSink.reasonOf(e));
            }
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
        String s = path.toString().replace('\\', '/');
        return !s.contains("/target/") && !s.contains("/node_modules/") && !s.contains("/.git/");
    }
}
