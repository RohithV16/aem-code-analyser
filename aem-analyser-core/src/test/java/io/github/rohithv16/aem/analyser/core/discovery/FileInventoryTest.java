package io.github.rohithv16.aem.analyser.core.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #16: one pruned walk, typed views, and no descent into build/VCS/dependency subtrees.
 */
class FileInventoryTest {

    @Test
    void prunesHeavySubtreesAndNeverVisitsTheirEntries(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/components/hero"));
        Files.writeString(root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/components/hero/hero.html"),
                "<div data-sly-use.model=\"com.acme.HeroModel\"></div>");
        Files.writeString(root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/components/hero/.content.xml"),
                "<jcr:root jcr:primaryType=\"cq:Component\"/>");
        Files.createDirectories(root.resolve("core/src/main/java/com/acme"));
        Files.writeString(root.resolve("core/src/main/java/com/acme/HeroModel.java"), "package com.acme; class HeroModel {}");

        // 500 node_modules packages with one Java file each, plus every other pruned tree.
        for (int i = 0; i < 500; i++) {
            Path packageDir = root.resolve("ui.frontend/node_modules/pkg-" + i);
            Files.createDirectories(packageDir);
            Files.writeString(packageDir.resolve("Generated" + i + ".java"), "class Generated" + i + " {}");
        }
        for (String pruned : List.of(".git", "target", ".idea", ".vscode", "vault-work")) {
            Path dir = root.resolve(pruned).resolve("nested");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("Ignored.java"), "class Ignored {}");
        }

        FileInventory inventory = FileInventory.scan(root);

        assertEquals(3, inventory.fileCount(), "only the three real project files are inventoried");
        assertEquals(1, inventory.javaFiles().size());
        assertEquals(1, inventory.templates().size());
        assertEquals(1, inventory.contentXml().size());
        assertTrue(inventory.clientlibDefinitions().isEmpty(), "no clientlib path in this fixture");
        assertEquals(6, inventory.prunedDirectoryCount(), "500 node_modules packages share one pruned root");
        for (Path file : inventory.files()) {
            String normalized = file.toString().replace('\\', '/');
            assertFalse(normalized.contains("/node_modules/"), normalized);
            assertFalse(normalized.contains("/target/"), normalized);
            assertFalse(normalized.contains("/.git/"), normalized);
            assertFalse(normalized.contains("/vault-work/"), normalized);
        }
        assertTrue(inventory.contains(root.resolve("core/src/main/java/com/acme/HeroModel.java")));
    }

    @Test
    void typedViewsAndSubtreeFiltersComeFromTheSameSnapshot(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/clientlib-site"));
        Files.writeString(root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/clientlib-site/.content.xml"),
                "<jcr:root/>");
        Files.createDirectories(root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/components/a"));
        Files.writeString(root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/components/a/.content.xml"),
                "<jcr:root jcr:primaryType=\"cq:Component\"/>");
        Files.createDirectories(root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/components/a/b"));
        Files.writeString(root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/components/a/b/template.html"),
                "<div></div>");

        FileInventory inventory = FileInventory.scan(root);

        assertEquals(List.of(root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/clientlib-site/.content.xml")),
                inventory.clientlibDefinitions());
        assertEquals(2, inventory.filesUnder(
                root.resolve("ui.apps/src/main/content/jcr_root/apps/acme/components")).size());

        // Deleting the tree after the scan must not change what the views return: the inventory is
        // a snapshot of one walk, not a live filesystem query re-walked per consumer.
        deleteRecursively(root.resolve("ui.apps"));
        assertEquals(1, inventory.templates().size());
        assertEquals(2, inventory.contentXml().size());
        assertNotEquals(0, inventory.filesUnder(root).size());
    }

    @Test
    void emptyInventoryForMissingRoot() {
        FileInventory inventory = FileInventory.scan(Path.of("/definitely/not/a/real/path/w2"));
        assertTrue(inventory.isEmpty());
        assertEquals(0, inventory.fileCount());
        assertTrue(inventory.filesUnder(Path.of("/definitely/not/a/real/path/w2")).isEmpty());
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }
}
