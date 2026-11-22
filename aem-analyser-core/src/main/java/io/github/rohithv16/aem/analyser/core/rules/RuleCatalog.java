package io.github.rohithv16.aem.analyser.core.rules;

import io.github.rohithv16.aem.analyser.core.model.report.RuleId;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud002Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud003Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud004Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud005Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud006Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud007Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud008Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemCloud009Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemComp001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemComp002Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead002ARule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead002BRule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead003ARule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead003BRule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead003CRule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead004Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead005Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDead007Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDup001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDup002Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemDup003Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemPerf001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemPerf002Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemPerf003Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemPerf004Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual002Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual003Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual004Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual005Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemQual008Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemSec001Rule;
import io.github.rohithv16.aem.analyser.core.rules.impl.AemSec002Rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single source of truth for which rule evaluators are active.
 *
 * <p>The registry is validated against {@link RuleId} when this class is initialised: an evaluator
 * that reports an unknown id, two evaluators claiming one id, or a rule id with no producer all fail
 * immediately instead of silently changing what a report contains. Rule ids produced outside this
 * catalog (AEM-DUP-010 by PMD CPD, the retired AEM-QUAL-006) are declared explicitly.
 */
public final class RuleCatalog {

    /** Rule ids that deliberately have no {@link RuleEvaluator} here. */
    public static final Set<String> PRODUCED_ELSEWHERE =
            Collections.unmodifiableSet(new java.util.LinkedHashSet<>(List.of(
                    // Produced by discovery/PmdCpdDuplicationEngine (PMD CPD), not by a rule class.
                    RuleId.AEM_DUP_010.id(),
                    // Retired: identical to AEM-QUAL-004, kept only for baseline/SARIF continuity.
                    RuleId.AEM_QUAL_006.id())));

    /**
     * Ids that no longer have any producer. Unlike {@link #PRODUCED_ELSEWHERE}, these are reported as
     * disabled in SARIF because nothing can ever emit them.
     */
    public static final Set<String> RETIRED = Collections.unmodifiableSet(
            new java.util.LinkedHashSet<>(List.of(RuleId.AEM_QUAL_006.id())));

    /**
     * Replacement for each retired id, so every surface can name the rule that took over instead of
     * leaving a user to guess. A retired id resolves silently otherwise: a config or baseline that
     * disables it looks like a covered rule while nothing runs.
     */
    private static final Map<String, String> RETIRED_REPLACEMENTS = Map.of(
            // Identical check, originally split across two ids; AEM-QUAL-004 is the live one.
            RuleId.AEM_QUAL_006.id(), RuleId.AEM_QUAL_004.id());

    /** True when {@code ruleId} names a retired rule (case-insensitive). */
    public static boolean isRetired(String ruleId) {
        return ruleId != null && RETIRED.contains(normalize(ruleId));
    }

    /** @return the id that replaced a retired rule, empty for live or unknown ids */
    public static java.util.Optional<String> replacementFor(String ruleId) {
        return ruleId == null
                ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(RETIRED_REPLACEMENTS.get(normalize(ruleId)));
    }

    /** The retirement note every surface prints, e.g. {@code RETIRED — replaced by AEM-QUAL-004}. */
    public static String retirementNote(String ruleId) {
        if (!isRetired(ruleId)) {
            return "";
        }
        return replacementFor(ruleId)
                .map(replacement -> "RETIRED — replaced by " + replacement)
                .orElse("RETIRED — no replacement");
    }

    private static String normalize(String ruleId) {
        return ruleId.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static final Map<String, RuleEvaluator> BY_ID = validate(List.of(
            new AemDead001Rule(),
            new AemDead002ARule(),
            new AemDead002BRule(),
            new AemDead003ARule(),
            new AemDead003BRule(),
            new AemDead003CRule(),
            new AemDead004Rule(),
            new AemDead005Rule(),
            new AemDead007Rule(),
            new AemComp001Rule(),
            new AemComp002Rule(),
            new AemDup001Rule(),
            new AemDup002Rule(),
            new AemDup003Rule(),
            new AemQual001Rule(),
            new AemQual002Rule(),
            new AemQual003Rule(),
            new AemQual004Rule(),
            new AemQual005Rule(),
            new AemQual008Rule(),
            new AemPerf001Rule(),
            new AemPerf002Rule(),
            new AemPerf003Rule(),
            new AemPerf004Rule(),
            new AemCloud001Rule(),
            new AemCloud002Rule(),
            new AemCloud003Rule(),
            new AemCloud004Rule(),
            new AemCloud005Rule(),
            new AemCloud006Rule(),
            new AemCloud007Rule(),
            new AemCloud008Rule(),
            new AemCloud009Rule(),
            new AemSec001Rule(),
            new AemSec002Rule()));

    private RuleCatalog() {
    }

    /** Rule evaluators in a stable order, keyed by rule id. */
    public static Map<String, RuleEvaluator> evaluators() {
        return BY_ID;
    }

    public static List<RuleEvaluator> ordered() {
        return List.copyOf(BY_ID.values());
    }

    private static Map<String, RuleEvaluator> validate(List<RuleEvaluator> evaluators) {
        Set<String> known = java.util.Arrays.stream(RuleId.values())
                .map(RuleId::id)
                .collect(Collectors.toCollection(TreeSet::new));

        Map<String, RuleEvaluator> byId = new LinkedHashMap<>();
        for (RuleEvaluator evaluator : evaluators) {
            String id = evaluator.getRuleId();
            if (!known.contains(id)) {
                throw new IllegalStateException("RuleEvaluator " + evaluator.getClass().getName()
                        + " reports '" + id + "', which is not a RuleId");
            }
            RuleEvaluator previous = byId.put(id, evaluator);
            if (previous != null) {
                throw new IllegalStateException("Two evaluators claim " + id + ": "
                        + previous.getClass().getName() + " and " + evaluator.getClass().getName());
            }
        }

        List<String> missing = known.stream()
                .filter(id -> !byId.containsKey(id))
                .filter(id -> !PRODUCED_ELSEWHERE.contains(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("RuleId values without a RuleEvaluator: " + missing
                    + " - register one or add the id to RuleCatalog.PRODUCED_ELSEWHERE");
        }
        return Collections.unmodifiableMap(byId);
    }

    /** Convenience for callers that only need the ids, in registry order. */
    public static List<String> ruleIds() {
        return new ArrayList<>(BY_ID.keySet());
    }

    static <T> List<T> dedupe(List<T> values, Function<T, String> key) {
        return new ArrayList<>(values.stream()
                .collect(Collectors.toMap(key, Function.identity(), (a, b) -> a, LinkedHashMap::new))
                .values());
    }
}
