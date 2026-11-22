package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.discovery.FileInventory;
import io.github.rohithv16.aem.analyser.core.discovery.HtlMarkupScanner;
import io.github.rohithv16.aem.analyser.core.discovery.HtlMarkupScanner.MarkupTag;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;

import java.nio.file.Path;
import java.util.List;

/**
 * Shared plumbing for the accessibility rules: one markup scan over the project's templates and
 * the two conversions every finding needs.
 */
final class A11yMarkup {

    private A11yMarkup() {
    }

    /** Every start tag in the project's templates, using the run's shared inventory when present. */
    static List<MarkupTag> tags(RuleContext context) {
        FileInventory inventory = context.inventory();
        if (inventory.isEmpty() && context.projectRoot() != null) {
            // Unit tests and callers without a shared inventory still get the pruned walk.
            inventory = FileInventory.scan(context.projectRoot());
        }
        return new HtlMarkupScanner().scanTemplates(inventory);
    }

    static String relativePath(RuleContext context, Path file) {
        return file == null ? "" : context.relativePath(file);
    }

    static TextRegion region(MarkupTag tag) {
        int line = Math.max(1, tag.line());
        int column = Math.max(1, tag.column());
        return new TextRegion(line, column, line, column + Math.max(1, tag.name().length() + 1));
    }
}
