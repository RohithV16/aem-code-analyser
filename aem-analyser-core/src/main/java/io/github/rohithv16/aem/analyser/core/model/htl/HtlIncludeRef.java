package io.github.rohithv16.aem.analyser.core.model.htl;

import java.nio.file.Path;

/**
 * A literal {@code data-sly-include} target found in an HTL template.
 *
 * <p>Only statically resolvable targets reach this record: a dynamic expression
 * ({@code ${model.template}}) or an absolute repository path ({@code /libs/...}) cannot be
 * resolved against the analysed file tree and is not recorded. The rule that consumes this
 * list resolves {@link #target()} against the including template's directory.</p>
 */
public record HtlIncludeRef(
        Path filePath,
        int line,
        int column,
        String target
) {
}
