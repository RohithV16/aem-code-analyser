package io.github.rohithv16.aem.analyser.core.model.report;

import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;

/**
 * A secondary location referenced by a violation (e.g. the OCD definition related to a ghost property).
 *
 * @param message   explanation of the relation
 * @param filePath  reactor-root-relative, '/'-separated path
 * @param region    1-based text region
 */
public record RelatedLocation(String message, String filePath, TextRegion region) {

    public RelatedLocation {
        if (message == null) {
            message = "Related location";
        }
        if (region == null) {
            region = TextRegion.forWholeFile();
        }
    }
}
