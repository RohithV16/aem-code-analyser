# C2 — Runmode index key mismatch

Status: Done (wave 1, 2026-11-15)
Priority: P0
Area: aem-analyser-core / `graph.ReferenceGraphIndex`

Verified evidence: `registerConfig` stored configs under a joined key (`"author+dev"`) while
`getConfigsForRunmode("author")` looked up a single token, so every single-runmode lookup missed.

Problem: the secondary index was unusable; any future runmode-aware rule would silently see zero
configs.

Desired behavior: a config active in N runmodes is retrievable by each individual runmode
(case-insensitive), and configs with no runmode are indexed under the empty key.

Files to change:
- `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/graph/ReferenceGraphIndex.java`

Implementation steps (landed):
1. Lowercase, filter, de-duplicate and sort each runmode before indexing.
2. Index under every individual runmode; use `""` when the runmode set is empty.
3. Dropped the unused `Collectors` import.

Tests to add (landed):
- `graph/ReferenceGraphIndexTest` — multi-runmode config resolves via each token; case-insensitive;
  unknown runmode returns empty.

Acceptance criteria: `getConfigsForRunmode("author")` and `"dev"` both return a config registered for
`{author, dev}`; `mvn -pl aem-analyser-core test` green.

Risks / notes: no production caller queries the index yet; the fix protects the API contract.
