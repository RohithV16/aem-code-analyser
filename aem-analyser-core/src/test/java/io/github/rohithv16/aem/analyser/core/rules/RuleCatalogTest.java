package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleCatalogTest {

    /**
     * Guards the invariant that produced two shipped defects: a RuleId with no live producer
     * (AEM-DUP-010 had a dead second evaluator) and two producers for one id (AEM-QUAL-006
     * double-reported every resolver field).
     */
    @Test
    void everyRuleIdHasExactlyOneOwner() {
        var registered = RuleCatalog.evaluators().keySet();
        var declaredElsewhere = RuleCatalog.PRODUCED_ELSEWHERE;

        for (RuleId rule : RuleId.values()) {
            boolean owned = registered.contains(rule.id()) || declaredElsewhere.contains(rule.id());
            assertTrue(owned, "no producer for " + rule.id());
        }
        for (String id : registered) {
            assertFalse(declaredElsewhere.contains(id),
                    id + " is both registered and declared as produced elsewhere");
        }
    }

    @Test
    void retiredIdsAreASubsetOfIdsWithoutAnEvaluator() {
        assertTrue(RuleCatalog.PRODUCED_ELSEWHERE.containsAll(RuleCatalog.RETIRED),
                "a retired id has no producer, so it must also be declared as produced elsewhere");
        for (String id : RuleCatalog.RETIRED) {
            assertFalse(RuleCatalog.evaluators().containsKey(id), id + " is retired but still registered");
        }
    }

    @Test
    void evaluatorIdsMatchTheirRegistryKeys() {
        RuleCatalog.evaluators().forEach((id, evaluator) ->
                assertEquals(id, evaluator.getRuleId(),
                        evaluator.getClass().getSimpleName() + " reports a different id"));
    }

    @Test
    void catalogCoversEveryRuleIdExactlyOnce() {
        assertEquals(37, RuleCatalog.evaluators().size());
        assertEquals(RuleId.values().length - RuleCatalog.PRODUCED_ELSEWHERE.size(),
                RuleCatalog.evaluators().size(),
                "registered + produced elsewhere must cover every RuleId");
        assertSame(RuleCatalog.evaluators(), RuleCatalog.evaluators(), "registry must be built once");
        assertEquals(List.copyOf(RuleCatalog.evaluators().keySet()), RuleCatalog.ruleIds());
    }
}
