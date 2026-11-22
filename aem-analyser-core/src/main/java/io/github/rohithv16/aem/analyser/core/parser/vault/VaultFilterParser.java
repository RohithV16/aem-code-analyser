package io.github.rohithv16.aem.analyser.core.parser.vault;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import io.github.rohithv16.aem.analyser.core.discovery.FileInventory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads FileVault {@code META-INF/vault/filter.xml} files: the {@code workspaceFilter} entries a
 * content package ships, each with its {@code root} path and {@code mode}.
 *
 * <p>Parsing is SAX, not DOM, only because SAX exposes a {@link Locator}: the rule has to report
 * {@code filter.xml:<line>} and a DOM would need a second pass to recover line numbers. DTDs and
 * external entities are disabled — the file comes from the analysed repository.
 */
public final class VaultFilterParser {

    private static final String FILTER_NAME = "filter.xml";
    private static final String FILTER_PATH_SUFFIX = "src/main/content/META-INF/vault/filter.xml";
    private static final String[] SKIP_SEGMENTS =
            {"/target/", "/vault-work/", "/node_modules/", "/.git/"};

    /**
     * @param module the content module the filter came from (module directory name)
     * @param root   the JCR path rooted by the filter
     * @param mode   the declared merge mode, never null ({@code merge} when absent)
     * @param file   absolute {@code filter.xml} path
     * @param line   1-based line of the {@code <filter>} element
     */
    public record FilterRoot(String module, String root, String mode, Path file, int line) {
    }

    /**
     * Finds and parses every content-module filter under {@code projectRoot}.
     *
     * <p>Only the authored copy is read: {@code target/} and {@code vault-work/} hold copies produced
     * by the build, so reading them would report the same filter twice, once per build.
     */
    public List<FilterRoot> scan(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return List.of();
        }
        List<FilterRoot> filters = new ArrayList<>();
        try {
            // Pruned walk: a filter after Files.walk still visits every node_modules entry.
            for (Path file : FileInventory.walkPruned(projectRoot, 0,
                    path -> Files.isRegularFile(path) && isAuthoredFilter(path))) {
                filters.addAll(parse(file, moduleOf(projectRoot, file)));
            }
        } catch (IOException ignored) {
            // An unreadable tree simply yields no filters.
        }
        return filters;
    }

    /**
     * Parses one {@code filter.xml}. Malformed XML yields no entries: the contract is a report, not a
     * build failure, and a package that cannot be parsed cannot be shown to overlap anything.
     */
    public List<FilterRoot> parse(Path filterXml, String module) {
        List<FilterRoot> roots = new ArrayList<>();
        try (InputStream input = Files.newInputStream(filterXml)) {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.newSAXParser().parse(new InputSource(input),
                    new FilterHandler(module, filterXml, roots));
        } catch (IOException | SAXException | ParserConfigurationException unreadable) {
            return List.of();
        }
        return roots;
    }

    private static boolean isAuthoredFilter(Path file) {
        if (!FILTER_NAME.equals(file.getFileName().toString())) {
            return false;
        }
        String normalized = file.toString().replace('\\', '/');
        if (!normalized.endsWith(FILTER_PATH_SUFFIX)) {
            return false;
        }
        for (String segment : SKIP_SEGMENTS) {
            if (normalized.contains(segment)) {
                return false;
            }
        }
        return true;
    }

    /** The first path segment below the project root, which is the module directory name. */
    private static String moduleOf(Path projectRoot, Path file) {
        try {
            Path relative = projectRoot.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize());
            if (relative.getNameCount() > 0) {
                return relative.getName(0).toString();
            }
        } catch (IllegalArgumentException ignored) {
            // Different filesystem provider: fall through to the directory name.
        }
        Path parent = file.getParent();
        return parent == null ? "" : parent.toString();
    }

    private static final class FilterHandler extends DefaultHandler {

        private final String module;
        private final Path file;
        private final List<FilterRoot> roots;
        private Locator locator;

        private FilterHandler(String module, Path file, List<FilterRoot> roots) {
            this.module = module;
            this.file = file;
            this.roots = roots;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if (!"filter".equals(qName) && !"filter".equals(localName)) {
                return;
            }
            String root = attributes.getValue("root");
            if (root == null || root.isBlank()) {
                return;
            }
            String mode = attributes.getValue("mode");
            roots.add(new FilterRoot(module, root.strip(),
                    mode == null || mode.isBlank() ? "merge" : mode.strip().toLowerCase(java.util.Locale.ROOT),
                    file, locator == null ? 1 : Math.max(1, locator.getLineNumber())));
        }
    }
}
