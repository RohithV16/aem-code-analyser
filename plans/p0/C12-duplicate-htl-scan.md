# C12 — Reactor root was scanned as a content module (duplicate findings)

Status: Done (wave 1, 2026-11-15)
Priority: P0
Area: aem-analyser-core / `api.DefaultAemCodeAnalyser`, `discovery.ProjectDiscoveryEngine`

Verified evidence from the real-project baseline: the root aggregator POM is emitted as a module and
`HtlScanner`/`JcrComponentScanner` walk its directory recursively, so `ui.apps` was scanned twice.
That doubled HTL usages (20 instead of 10) and emitted duplicate `AEM-SEC-001` results at the same
file/line.

Problem: duplicate findings and inflated statistics; the gate reports the same defect twice.

Desired behavior: only content-bearing module classifications are scanned for HTL/JCR artifacts, and
identical findings are collapsed as a safety net.

Files to change (landed):
- `DefaultAemCodeAnalyser` — `mayContainContent(ModuleClassification)` guard around the HTL/JCR loop.
- `DefaultAemCodeAnalyser` — `deduplicate(List<Violation>)` keyed by rule, path, line, column, subtype.

Implementation steps:
1. Restrict scanning to `APPS_MODULE`, `CONTENT_MODULE`, `CONTAINER_MODULE`, `STRUCTURE_MODULE`,
   `ACL_MODULE`, `UNCLASSIFIED`.
2. Skip `AGGREGATOR_MODULE`, `CONFIG_MODULE`, `BUNDLE_MODULE`, `DISPATCHER_MODULE`,
   `FRONTEND_MODULE`, `TEST_MODULE`, `PIPELINE_MODULE`.
3. De-duplicate after the final sort so output order remains deterministic.

Tests to add:
- Discovery/analysis fixture with a root aggregator + `ui.apps`: HTL usages counted once and no
  duplicate `(rule, file, line, column)` tuples (extend `EndToEndAnalysisTest` or add a dedicated
  multi-module fixture).
- Unit test for the dedupe key if extracted.

Acceptance criteria: portfolio baseline shows `HtlUsages` equal to the source count and zero
duplicate tuples; `Modules` still equals the reactor POM count.

Risks / notes: a module classified `UNCLASSIFIED` is still scanned to avoid false negatives; tighten
only with fixture evidence.
