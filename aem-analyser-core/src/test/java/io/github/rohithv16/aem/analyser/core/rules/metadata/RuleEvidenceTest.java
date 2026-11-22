package io.github.rohithv16.aem.analyser.core.rules.metadata;

import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #23: the jar's evidence resource carries one pair per active rule and no pair for a retired id. */
class RuleEvidenceTest {

    @Test
    void everyActiveRuleHasBothSidesAndTheRetiredIdHasNone() {
        List<RuleEvidence.Row> rows = RuleEvidence.all();
        assertEquals(RuleId.values().length, rows.size(),
                "one row per registry id, including the retired id");

        List<RuleEvidence.Row> missingTwin = rows.stream()
                .filter(row -> "active".equals(row.status()))
                .filter(row -> row.positiveProbe() == null || row.negativeProbe() == null
                        || row.positiveLine() == null)
                .toList();
        assertTrue(missingTwin.isEmpty(), "active rules without a two-sided probe: "
                + missingTwin.stream().map(RuleEvidence.Row::id).toList());

        RuleEvidence.Row retired = RuleEvidence.find("AEM-QUAL-006").orElseThrow();
        assertEquals("retired", retired.status());
        assertNull(retired.positiveProbe());
        assertNull(retired.negativeProbe());
        assertEquals("AEM-QUAL-004", retired.replacedBy());
    }

    @Test
    void rowsPointAtTheDocumentationAnchorAndExactProbe() {
        RuleEvidence.Row row = RuleEvidence.find("aem-dead-004").orElseThrow();

        assertEquals("AEM-DEAD-004", row.id());
        assertEquals("aem-dead-004", row.docAnchor());
        assertEquals("ui.apps/src/main/content/jcr_root/apps/probe/components/probe/probe.html",
                row.positiveProbe());
        assertEquals(1, row.positiveLine());
        assertEquals("BROKEN_HTL_REFERENCE", row.positiveSubtype());
        assertEquals("ui.apps/src/main/content/jcr_root/apps/probe/components/probe/probe.html",
                row.negativeProbe());
        assertFalse(row.positiveProbe().isBlank());
        assertNotNull(row.since());
    }
}
