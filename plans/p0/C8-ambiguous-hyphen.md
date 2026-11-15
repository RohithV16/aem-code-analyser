# C8 — `ambiguousHyphen` is computed but never consumed

Status: Done (wave 2, 2026-11-15)
Priority: P0
Area: aem-analyser-core / `parser.config.ConfigFilePidResolver`, `graph.ReferenceGraphBuilder`

Verified evidence: `ResolvedPid.ambiguousHyphen()` is propagated through `OsgiConfigDefinition` into
`ReferenceGraphBuilder`, but no code reads it. Legacy `.config` names such as `my-service.pid-instance`
or `com.day.cq.foo-bar.1.0` can be read either as a hyphenated PID or as `pid-factory`.

Problem: a wrong split produces a false `ORPHANED_CONFIGURATION` (AEM-DEAD-001).

Desired behavior: for ambiguous legacy configs, re-resolve against the known component active PIDs and
pick the interpretation that matches exactly one component; otherwise keep the split and emit a
diagnostic.

Files to change:
- `aem-analyser-core/.../graph/ReferenceGraphBuilder.java` — before creating `CONFIGURES`, if
  `definition.factoryAmbiguous()`, test both interpretations against `ReferenceGraphIndex`.
- `aem-analyser-core/.../parser/config/ConfigFilePidResolver.java` — expose the two candidate PIDs
  (full base name vs split prefix) so the builder does not re-parse strings.
- `core/model/report/AnalysisDiagnostic.java` (E11) — optional `AMBIGUOUS_PID` diagnostic.

Implementation steps:
1. Add `List<String> candidatePids()` to `ResolvedPid` (or a helper) returning `[splitPid, wholeName]`.
2. In the builder, count components matching each candidate; if exactly one candidate matches, use it.
3. If both/neither match, keep the current split and record the diagnostic (E11).
4. Leave non-ambiguous configs untouched.

Tests to add:
- `ReferenceGraphBuilder` fixture: legacy `.config` for a hyphenated PID with a real component resolves
  (no orphan).
- Ambiguous fixture with no matching component stays a single orphan finding and records one diagnostic.

Acceptance criteria: ambiguous legacy fixtures resolve correctly; no increase in FPs on the portfolio
baseline.

Risks / notes: requires the component index to be populated before config edges are created — the
builder already has a two-phase ingestion order, so do this in the config-binding phase.
