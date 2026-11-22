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
 * Rule AEM-A11Y-002 — an HTL anchor that is not operable or has no accessible name.
 *
 * <p>Two shapes are reported, one finding per anchor:</p>
 *
 * <ul>
 *   <li>no {@code href} and no {@code data-sly-attribute.href}: the element is styled as a link
 *       but is not focusable, so keyboard users cannot reach it (WCAG 2.4.4);</li>
 *   <li>an {@code href} whose only content is an image or icon and which exposes no accessible
 *       name — no text, no {@code aria-label}/{@code aria-labelledby}/{@code title}, and no
 *       descendant image with a non-blank {@code alt} (WCAG 2.4.4/4.1.2).</li>
 * </ul>
 *
 * <p>Anchors used as in-page targets ({@code name} or {@code id} without {@code href}), anchors
 * that deliberately carry a dynamic attribute map, and anchors whose closing tag is missing (the
 * content cannot be judged) are never reported.</p>
 */
public final class AemA11y002Rule implements RuleEvaluator {

    @Override
    public String getRuleId() {
        return RuleId.AEM_A11Y_002.id();
    }

    @Override
    public List<Violation> evaluate(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        for (MarkupTag tag : A11yMarkup.tags(context)) {
            if (!tag.is("a") || tag.hasAttribute("data-sly-element")) {
                continue;
            }
            boolean dynamicHref = tag.hasAttribute("data-sly-attribute.href")
                    || tag.hasAttribute("data-sly-attribute");
            boolean hasHref = tag.hasAttribute("href") || dynamicHref;
            if (!hasHref) {
                if (isAnchorTarget(tag) || isScriptedButton(tag)) {
                    continue;
                }
                violations.add(violation(context, tag, "ANCHOR_WITHOUT_HREF",
                        "<a> has neither href nor data-sly-attribute.href, so it is not focusable"
                                + " and keyboard users cannot activate it (WCAG 2.4.4).",
                        "Add href (bind it with data-sly-attribute.href when it is dynamic)."));
                continue;
            }
            if (dynamicHref && !tag.hasAttribute("href")) {
                // The href is supplied at render time; there is nothing static left to judge.
                continue;
            }
            if (hasAccessibleName(tag) || tag.innerHtml() == null) {
                continue;
            }
            violations.add(violation(context, tag, "ANCHOR_WITHOUT_ACCESSIBLE_NAME",
                    "<a> renders only an image or icon and exposes no accessible name; a screen"
                            + " reader announces an empty link (WCAG 2.4.4).",
                    "Add aria-label (or visually hidden text) naming the link's destination."));
        }
        return violations;
    }

    private static Violation violation(RuleContext context, MarkupTag tag, String subtype,
                                       String message, String remediation) {
        return new Violation(
                RuleId.AEM_A11Y_002.id(),
                RuleId.AEM_A11Y_002.defaultLevel(),
                subtype,
                message,
                A11yMarkup.relativePath(context, tag.filePath()),
                A11yMarkup.region(tag),
                null,
                remediation,
                List.of());
    }

    private static boolean isAnchorTarget(MarkupTag tag) {
        // <a name="section"> / <a id="section"> is a valid link target and needs no href.
        return tag.hasNonBlankAttribute("name") || tag.hasNonBlankAttribute("id");
    }

    private static boolean isScriptedButton(MarkupTag tag) {
        String role = tag.attribute("role");
        return role != null
                && "button".equals(role.strip().toLowerCase(Locale.ROOT))
                && tag.hasAttribute("tabindex");
    }

    private static boolean hasAccessibleName(MarkupTag tag) {
        if (tag.hasNonBlankAttribute("aria-label")
                || tag.hasNonBlankAttribute("aria-labelledby")
                || tag.hasNonBlankAttribute("title")
                || tag.hasAttribute("data-sly-attribute.aria-label")) {
            return true;
        }
        if (tag.textContent() != null && !tag.textContent().isBlank()) {
            return true;
        }
        String inner = tag.innerHtml();
        if (inner == null) {
            return true;
        }
        if (HtlMarkupScanner.hasImageWithAlt(inner)) {
            return true;
        }
        // An inline SVG can name itself with a <title> child.
        return inner.toLowerCase(Locale.ROOT).contains("<title");
    }
}
