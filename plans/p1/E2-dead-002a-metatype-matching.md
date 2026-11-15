# E2 — AEM-DEAD-002A metatype matching and property whitelist

Status: Verified
Priority: P1
Area: aem-analyser-core / `rules.impl.AemDead002ARule`, `graph.MetatypeNameNormalizer`,
`parser.java.visitor.MetatypeOcdVisitor`, `rules.catalogs.FrameworkPropertyWhitelist`,
`parser.config.CfgJsonConfigParser`

Problem / opportunity:
Wave-1 status (2026-11-15) — **already implemented, keep the remaining items**:
- C4 landed: `AemDead002ARule` now unions the OCDs of every configuring component, compares keys
  case-sensitively and no longer normalises config keys with `normalizeMethodName`.
- Remaining E2 work: inherited OCD `extends` attributes (gap 4), `@ComponentPropertyType`-derived
  OCDs (E3), and a case-sensitivity regression fixture.

Already handled (verified — do not re-implement):
- Explicit `@AttributeDefinition(id = "…")` wins in `MetatypeOcdVisitor.extractAttributeDefinition`
  (l.88-107) and in `ReferenceGraphBuilder` CONSUMES_PROPERTY remap (l.197-205).
- RFC 724 typed keys (`:Integer`, `:String[]`) are stripped by `ConfigKeyParser.parseKey`.
- Keys starting with `:` (RFC 724 comments / Felix metadata) are preserved untouched and are
  whitelisted by the `":"` prefix in `FrameworkPropertyWhitelist.PREFIX_KEYS`.
- `$[env:…]` / `$[secret:…]` / `$[property:…]` placeholders are detected and stored
  (`ConfigPropertyEntry.envPlaceholder`), including `;default=` extraction.
- Arrays and objects are stored as `DataType.ARRAY` / `OBJECT`.
Real gaps:
1. `AemDead002ARule` normalises the **config key** with `MetatypeNameNormalizer.normalizeMethodName`
   (l.53-54). That function is the inverse mapping for *Java method names*; applying it to a key such
   as `a_b` turns it into `a.b` and invents a match. (Same defect as C4 — fix once, in the shared
   helper, and reference C4 from here.)
2. Matching is `equalsIgnoreCase` (l.59-62). OSGi property keys are case-sensitive, so `Foo` and
   `foo` are treated as the same attribute. `FrameworkPropertyWhitelist.isWhitelisted` also
   lowercases (l.61), so a genuinely ghost key `Service.ranking` is suppressed.
3. Only `components.get(0)` is consulted, so with two components bound to one PID the second
   component's OCD is ignored (C4).
4. Shared OCD: `OCD` methods are read from `type.getMembers()` only — an OCD interface that
   `extends` a base metatype interface loses all inherited attributes, producing false ghosts.
5. Whitelist prefixes are segment-unaware in the opposite direction too: `"service."` suppresses any
   future app key in that namespace, which is acceptable, but there is no test pinning it.

Desired behavior:
- Key comparison is exact and case-sensitive on the base key, after type-hint stripping only.
- Every component bound to the PID contributes its OCD attribute set (union).
- Inherited OCD attribute methods are included.
- Whitelist matching is case-sensitive and documented.

Files to change:
- `core/graph/MetatypeNameNormalizer.java` — add `normalizeConfigKey(String)` (identity + type-hint
  strip) and mark `normalizeMethodName` javadoc as method-name-only.
- `core/rules/impl/AemDead002ARule.java` — collect all `CONFIGURES` targets, union OCDs, exact
  comparison, drop the `equalsIgnoreCase` fallbacks.
- `core/rules/catalogs/FrameworkPropertyWhitelist.java` — remove `toLowerCase`, keep all constants
  lower-case (they already are).
- `core/parser/java/visitor/MetatypeOcdVisitor.java` — resolve `extends` for interfaces/annotations
  within the same compilation unit, and (fallback) record the parent FQN for a later pass.

Implementation steps:
1. Add `normalizeConfigKey`; switch `AemDead002ARule` and `ReferenceGraphBuilder.findBoundMethod`
   (l.317-328) to it.
2. Change the rule to build `Set<String> declaredNames` from every bound component's OCD, then test
   `declaredNames.contains(baseKey)`.
3. Add a second pass in `DefaultAemCodeAnalyser` (after all `JavaSourceParseResult`s are collected)
   that copies attribute methods from a parsed parent OCD into the child OCD when both are in the
   repository; keep behaviour unchanged when the parent is external (document as a known limitation).
4. Keep `BINDS_PROPERTY` edges in sync (same helper) so the graph and the rule cannot disagree.

Tests to add:
- `AemDead002ARuleTest` fixtures: key with `_` that must NOT be normalised (`a_b` vs `a.b` ghost);
  `Service.ranking` (must be reported once whitelist is case-sensitive — confirm against a real
  project first, otherwise keep `service.ranking` exact and document);
  two components bound to one PID with different OCDs (no ghost);
  `@AttributeDefinition(id="explicit-key")` + `explicit-key.cfg.json` (no ghost);
  inherited OCD attribute consumed from the parent interface.
- `MetatypeNameNormalizerTest`: `normalizeConfigKey("a_b:Integer") == "a_b"`.

Acceptance criteria:
- No `AEM-DEAD-002A` violation for any property whose exact base key appears in any bound OCD.
- No violation is produced by applying method-name normalisation to a config key.
- Existing `EndToEndAnalysisTest` FP baseline unchanged.

Risks / notes:
- Removing `equalsIgnoreCase` can surface violations previously hidden; run the portfolio validation
  and add any newly-found real keys to the whitelist only with a reproduction.
- `CfgJsonConfigParser.readRawArrayOrObject` loses nested object field names (writes `null` as the
  key token). That corrupts `rawValue` but not keys; fix under E7, not here.
