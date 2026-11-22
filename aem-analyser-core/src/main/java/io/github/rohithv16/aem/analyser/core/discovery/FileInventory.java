package io.github.rohithv16.aem.analyser.core.discovery;

import io.github.rohithv16.aem.analyser.core.model.report.DiagnosticKind;
import io.github.rohithv16.aem.analyser.core.util.DiagnosticSink;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One pruned, typed view of every file in a project tree.
 *
 * <p>Before this class every scanner and several rules walked the whole project root on their own:
 * six independent full-root traversals per run, each with a post-traversal filter that still paid
 * for every directory entry. On a real AEM reactor most of that tree is {@code node_modules} and
 * {@code target}, so the cost was dominated by paths that could never produce a finding.</p>
 *
 * <p>The inventory is built once per run with {@link Files#walkFileTree} and {@link
 * FileVisitResult#SKIP_SUBTREE} for build output, VCS metadata and frontend dependency trees, then
 * handed to every consumer through {@code RuleContext}. Scan cost no longer scales with
 * (rule count x tree size), and a new rule costs nothing extra.</p>
 *
 * <p>Order is the traversal order of the single walk, so a consumer that used to see
 * {@code Files.walk(moduleDirectory)} sees the same relative order from {@link #filesUnder(Path)}.
 * That keeps existing clients' "first file wins" behaviour byte-identical.</p>
 */
public final class FileInventory {

    /** Directory names that are never descended into, at any depth. */
    public static final Set<String> PRUNED_DIRECTORIES = Set.of(
            "node_modules", ".git", "target", ".idea", ".vscode", "vault-work");

    private static final FileInventory EMPTY = new FileInventory(List.of());

    private final Path root;
    private final List<Path> files;
    private final Set<Path> fileSet;
    private final List<Path> directories;
    private final List<Path> javaFiles;
    private final List<Path> templates;
    private final List<Path> contentXmlFiles;
    private final List<Path> clientlibDefinitions;
    private final int prunedDirectories;

    private FileInventory(List<Path> files) {
        this(null, files, List.of(), 0);
    }

    private FileInventory(Path root, List<Path> files, List<Path> directories, int prunedDirectories) {
        this.root = root;
        this.files = List.copyOf(files);
        this.fileSet = new LinkedHashSet<>(files);
        this.directories = List.copyOf(directories);
        this.prunedDirectories = prunedDirectories;

        List<Path> java = new ArrayList<>();
        List<Path> html = new ArrayList<>();
        List<Path> contentXml = new ArrayList<>();
        List<Path> clientlibs = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.endsWith(".java")) {
                java.add(file);
            }
            if (name.endsWith(".html")) {
                html.add(file);
            }
            if (".content.xml".equals(name)) {
                contentXml.add(file);
                String normalized = file.toString().replace('\\', '/');
                if (normalized.contains("/clientlib") || normalized.contains("/clientLib")) {
                    clientlibs.add(file);
                }
            }
        }
        this.javaFiles = List.copyOf(java);
        this.templates = List.copyOf(html);
        this.contentXmlFiles = List.copyOf(contentXml);
        this.clientlibDefinitions = List.copyOf(clientlibs);
    }

    /** An inventory that contains nothing; consumers fall back to a bounded walk. */
    public static FileInventory empty() {
        return EMPTY;
    }

    /**
     * Walks {@code root} once, pruning {@link #PRUNED_DIRECTORIES} subtrees.
     *
     * <p>Unreadable directories are reported as {@code UNREADABLE_TREE} diagnostics and skipped;
     * the rest of the tree is still inventoried.
     */
    public static FileInventory scan(Path root) {
        return scan(root, null);
    }

    public static FileInventory scan(Path root, DiagnosticSink diagnostics) {
        if (root == null || !Files.isDirectory(root)) {
            return EMPTY;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        List<Path> files = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        int[] pruned = {0};
        try {
            Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(normalizedRoot) && PRUNED_DIRECTORIES.contains(
                            dir.getFileName() == null ? "" : dir.getFileName().toString())) {
                        pruned[0]++;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    directories.add(dir.toAbsolutePath().normalize());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        files.add(file.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    if (diagnostics != null) {
                        diagnostics.add(DiagnosticKind.UNREADABLE_TREE, file,
                                DiagnosticSink.reasonOf(exc));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            if (diagnostics != null) {
                diagnostics.add(DiagnosticKind.UNREADABLE_TREE, normalizedRoot,
                        DiagnosticSink.reasonOf(e));
            }
        }
        return new FileInventory(normalizedRoot, files, directories, pruned[0]);
    }

    /** The root this inventory was built from, or {@code null} for {@link #empty()}. */
    public Path root() {
        return root;
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    /** Every inventoried regular file, in traversal order. */
    public List<Path> files() {
        return files;
    }

    /** Every {@code .java} file, in traversal order. */
    public List<Path> javaFiles() {
        return javaFiles;
    }

    /** Every {@code .html} file, in traversal order. */
    public List<Path> templates() {
        return templates;
    }

    /** Every {@code .content.xml} file, in traversal order. */
    public List<Path> contentXml() {
        return contentXmlFiles;
    }

    /** {@code .content.xml} files that live under a {@code clientlib*} directory. */
    public List<Path> clientlibDefinitions() {
        return clientlibDefinitions;
    }

    /** Files below {@code directory}; an empty list when the directory is not part of the tree. */
    public List<Path> filesUnder(Path directory) {
        return filesUnder(directory, file -> true);
    }

    /**
     * Files below {@code directory} that also match {@code filter}, in traversal order.
     *
     * <p>The subtree test uses normalized absolute paths, so callers pass the same rooted path they
     * used to pass to {@code Files.walk}. A filter after the single walk is O(inventoried files)
     * instead of O(tree), which is the whole point: the pruned tree is two orders of magnitude
     * smaller than the raw root.</p>
     */
    public List<Path> filesUnder(Path directory, Predicate<Path> filter) {
        if (directory == null) {
            return List.of();
        }
        Path normalized = directory.toAbsolutePath().normalize();
        List<Path> matches = new ArrayList<>();
        for (Path file : files) {
            if (file.startsWith(normalized) && filter.test(file)) {
                matches.add(file);
            }
        }
        return matches;
    }

    /** True when the file was inventoried, so callers can avoid a stat per candidate. */
    public boolean contains(Path file) {
        return file != null && fileSet.contains(file.toAbsolutePath().normalize());
    }

    /** Every inventoried regular file. */
    public int fileCount() {
        return files.size();
    }

    /** Directories visited (excluding pruned subtrees). */
    public int directoryCount() {
        return directories.size();
    }

    /** Directories skipped with {@code SKIP_SUBTREE}; useful for the console's phase line. */
    public int prunedDirectoryCount() {
        return prunedDirectories;
    }

    /** Case-insensitive suffix test that mirrors the scanners' extension matching. */
    public static Predicate<Path> withExtension(String... extensions) {
        List<String> lower = new ArrayList<>(extensions.length);
        for (String extension : extensions) {
            lower.add(extension.toLowerCase(Locale.ROOT));
        }
        return file -> {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            for (String extension : lower) {
                if (name.endsWith(extension)) {
                    return true;
                }
            }
            return false;
        };
    }
}
