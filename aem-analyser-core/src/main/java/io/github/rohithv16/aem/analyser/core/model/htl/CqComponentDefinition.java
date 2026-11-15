package io.github.rohithv16.aem.analyser.core.model.htl;

import java.nio.file.Path;

/**
 * Represents a scanned JCR cq:Component definition.
 */
public record CqComponentDefinition(
        Path componentDirectory,
        Path contentXml,
        String resourceType,
        String resourceSuperType,
        String title,
        String componentGroup,
        boolean hasClassicUiDialog,
        Path classicUiDialogXml
) {}
