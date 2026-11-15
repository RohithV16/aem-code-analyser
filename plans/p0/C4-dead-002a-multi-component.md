# C4 — `AEM-DEAD-002A` only checked the first component and misused the normalizer

Status: Done (wave 1, 2026-11-15)
Priority: P0
Area: aem-analyser-core / `rules.impl.AemDead002ARule`

Verified evidence: the rule read `components.get(0)` and applied
`MetatypeNameNormalizer.normalizeMethodName` to the **config key**, inventing matches (e.g. `a_b`
treated as `a.b`). Comparisons used `equalsIgnoreCase`, although OSGi property names are
case-sensitive.

Problem: false negatives for shared PIDs and false positives for underscore keys.

Desired behavior: a key is declared if **any** configuring component's OCD declares the exact
normalized property name; configs bound to a component without a resolvable OCD are skipped.

Files to change:
- `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/rules/impl/AemDead002ARule.java`

Implementation steps (landed):
1. Collect every `CONFIGURES` target and union their OCDs (deduplicated by FQN).
2. Skip the config when any configuring component has no resolvable OCD (cannot validate safely).
3. Compare the raw config key exactly against `getNormalizedOsgiPropName()` (which already honours
   explicit `@AttributeDefinition(id=...)`).
4. Attach a related location for every contributing OCD; describe all OCDs in the message.

Tests to add (landed):
- `rules/AemDead002ARuleTest` — property declared by the second component; ghost property; explicit
  id; case sensitivity (`Alpha` vs `alpha`); unresolved component is skipped.

Acceptance criteria: fixtures pass and `EndToEndAnalysisTest` stays green.

Risks / notes: skipping unresolved components is deliberately conservative; it trades a possible FN
for zero FPs. Inherited OCD attributes remain an E2 item.
