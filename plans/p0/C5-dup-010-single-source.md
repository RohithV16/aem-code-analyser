# C5 — `AEM-DUP-010` had two producers; one was dead code

Status: Done (wave 1, 2026-11-15)
Priority: P0
Area: aem-analyser-core / `rules.impl.AemDup010Rule`, `discovery.PmdCpdDuplicationEngine`

Verified evidence: `AemDup010Rule` implemented `AEM-DUP-010` but was **not registered** in
`DefaultAemCodeAnalyser.rules`. At runtime only `PmdCpdDuplicationEngine` emitted the rule, and the
class was referenced solely by `DuplicateRulesTest`. This was dead code that looked authoritative and
could be re-registered by mistake, producing duplicate findings.

Problem: two implementations of the same rule, only one live.

Desired behavior: exactly one producer of `AEM-DUP-010`, backed by the CPD engine that also computes
`CodeMetrics`.

Files to change:
- Deleted `aem-analyser-core/src/main/java/.../rules/impl/AemDup010Rule.java`
- `aem-analyser-core/src/test/java/.../rules/DuplicateRulesTest.java` — removed the dead-rule test and
  unused imports.

Implementation steps (landed):
1. Confirmed the rule was not in the registered rule list.
2. Deleted the class and its only test.
3. Kept `PmdCpdDuplicationEngine` as the single source (it feeds duplication metrics as well).

Tests to add:
- `PmdCpdDuplicationEngineTest` already covers the live producer; add an end-to-end assertion that a
  clone produces exactly one `AEM-DUP-010` (follow-up under O7).

Acceptance criteria: `grep -rn "AemDup010Rule"` returns nothing; `AEM-DUP-010` still fires via CPD.

Risks / notes: if a future rule needs graph context for duplication, reintroduce it as the sole
producer and remove the CPD emission — never both.
