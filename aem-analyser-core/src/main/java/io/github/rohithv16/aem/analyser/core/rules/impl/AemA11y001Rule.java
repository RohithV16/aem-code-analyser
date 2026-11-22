package io.github.rohithv16.aem.analyser.core.rules.impl;

import io.github.rohithv16.aem.analyser.core.discovery.FileInventory;
import io.github.rohithv16.aem.analyser.core.discovery.HtlMarkupScanner;
import io.github.rohithv16.aem.analyser.core.discovery.HtlMarkupScanner.MarkupTag;
import io.github.rohithv16.aem.analyser.core.model.graph.TextRegion;
import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.model.report.Violation;
import io.github.rohithv16.aem.analyser.core.rules.RuleContext;
import io.github.rohithv16.aem.analyser.core.rules.RuleEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rule AEM-A11Y-001 — an HTL {@code <img>} with no text alternative.
 *
 * <p>WCAG 1.1.1 requires every meaningful image to expose a text alternative. HTL escapes output
 * but renders the element as written, so an {@code alt} that is simply absent reaches production;
 * axe, pa11y and Lighthouse can only find it on a rendered page, long after the template was cheap
 * to fix. The rule reads the start tag from {@link HtlMarkupScanner}.</p>
 *
 * <p>An {@code alt} counts as present whatever its value, because {@code alt=""} is the documented
 * way to mark a decorative image; a dynamic value ({@code alt="${model.alt}"}) counts too. Images
 * that are removed from the accessibility tree ({@code aria-hidden="true"}, {@code
 * role="presentation"} or {@code role="none"}) are silent, as is a tag whose element name is
 * itself computed ({@code data-sly-element}), because the rendered element cannot be known.</p>
 */
public final class AemA11y001Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_A11Y_001.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (MarkupTag tag : A11yMarkup.tags(context)) {
            if (!tag.is("img") || isExempt(tag)) {
                continue;
            }
            violations.add(new Violation(
                    RuleId.AEM_A11Y_001.id(),
                    RuleId.AEM_A11Y_001.defaultLevel(),
                    "IMAGE_WITHOUT_ALT",
                    "<img> has no alt attribute and no data-sly-attribute.alt; screen readers cannot"
                            + " describe the image (WCAG 1.1.1).",
                    A11yMarkup.relativePath(context, tag.filePath()),
                    A11yMarkup.region(tag),
                    null,
                    "Add alt with a short text alternative, bind it with data-sly-attribute.alt, or"
                            + " use an empty alt=\"\" for a decorative image.",
                    List.of()));
        }
        return violations;
    }

    private static boolean isExempt(MarkupTag tag) {
        if (tag.hasAttribute("alt") || tag.hasAttribute("data-sly-attribute.alt")) {
            return true;
        }
        // A dynamic attribute map may supply alt at request time.
        if (tag.hasAttribute("data-sly-attribute")) {
            return true;
        }
        // The rendered element may not be an img at all.
        if (tag.hasAttribute("data-sly-element")) {
            return true;
        }
        if ("true".equalsIgnoreCase(tag.attribute("aria-hidden"))) {
            return true;
        }
        String role = tag.attribute("role");
        if (role != null) {
            String normalized = role.strip().toLowerCase(Locale.ROOT);
            if ("presentation".equals(normalized) || "none".equals(normalized)) {
                return true;
            }
        }
        return tag.hasAttribute("hidden");
    }
}
