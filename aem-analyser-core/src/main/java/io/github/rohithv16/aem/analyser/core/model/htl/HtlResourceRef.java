package io.github.rohithv16.aem.analyser.core.model.htl;

import java.nio.file.Path;

/**
 * A literal {@code resourceType} option on a {@code data-sly-resource} HTL attribute.
 *
 * <p>{@code data-sly-resource="foo/bar"} is a resource <em>path</em>, not a resource type, and is
 * not recorded here. Only an explicit {@code resourceType='...'} option whose value is a literal
 * is captured; a computed value ({@code resourceType=${model.type}}) is skipped because it cannot
 * be resolved statically.</p>
 */
public record HtlResourceRef(
        Path filePath,
        int line,
        int column,
        String resourceType
) {
}
