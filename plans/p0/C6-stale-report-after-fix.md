# C6 — `--fix` produced a stale report and exit code

Status: Done (wave 1, 2026-11-15)
Priority: P0
Area: aem-analyser-cli / `cli.AnalyserCli`

Verified evidence: `run()` analysed once, applied fixes, then exported and computed the exit code from
the **pre-fix** report. A project with a fixable finding still exited 1 and wrote SARIF/Sonar files
containing the fixed violation.

Problem: `--fix` reported success while the gate and artifacts contradicted it.

Desired behavior: after a non-dry-run fix that changed files, re-analyse and re-apply the git filter
before export/exit. Dry runs keep the pre-fix report (nothing changed on disk).

Files to change:
- `aem-analyser-cli/src/main/java/io/github/rohithv16/aem/analyser/cli/AnalyserCli.java`

Implementation steps (landed):
1. Extracted `analyzeAndFilter(context, root)` (analysis + `--changed-only` filtering).
2. Kept the `AnalysisContext` immutable and reused it for the second run.
3. Re-analyse only when `!fixDryRun && fixSummary.hasFixes()`, with an informational log line.

Tests to add:
- CLI-level fixture with one auto-fixable issue: after `--fix`, exit code is 0 and no SARIF result for
  the fixed rule; `--fix-dry-run` leaves the report unchanged.

Acceptance criteria: no stale violations in exports or exit code after a real fix.

Risks / notes: re-analysis doubles runtime on fix runs; acceptable for a developer-invoked command.
