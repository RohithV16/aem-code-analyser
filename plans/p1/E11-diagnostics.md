# E11 — Structured analysis diagnostics (replace silent skips)

Status: Done (wave 3, 2026-11-15)
Priority: P1
Area: aem-analyser-core / `api.DefaultAemCodeAnalyser`, `model.report.AnalysisReport`,
`cli.AnalyserCli`, `maven.CheckMojo`/`ReportMojo`

Problem / opportunity:
Verified silent skips that change results without any signal:
- `DefaultAemCodeAnalyser.parseJavaSourcesParallel` l.197-199 (`ExecutionException` → "unparseable
  file: skip").
- `DefaultAemCodeAnalyser.parseOsgiConfigs` l.228-230 (`IOException | RuntimeException` → skip).
- `DefaultAemCodeAnalyser.indexDependencies` l.247-249 (unreadable root → skip).
- `OsgiConfigScanner.scanModule` l.59-61 (unreadable subtree), `HtlScanner.scan` l.48,
  `JcrComponentScanner.scanDirectory` l.41, `ExportPackageCollector.fromBndFile` l.55,
  `DependencyBytecodeIndexer.index` l.48 (`malformed class: skip`).
Consequence: a file that fails to parse simply produces fewer violations, and the console, SARIF and
HTML reports look clean.

Desired behavior:
- Every skip is recorded as `AnalysisDiagnostic(kind, path, reason, line?)`.
- Diagnostics are summarised on the console, listed under `--verbose`, and attached to the SARIF run.
- The analysis never fails because of a diagnostic.

Files to change:
- `core/model/report/AnalysisDiagnostic.java` (new record) and `DiagnosticKind` enum
  (`JAVA_PARSE_ERROR`, `CONFIG_PARSE_ERROR`, `UNREADABLE_TREE`, `MALFORMED_CLASS`,
  `DUPLICATE_KEY`, `IGNORED_PATTERN`, `DEPENDENCY_INDEX`).
- `core/model/report/AnalysisReport.java` — add `List<AnalysisDiagnostic> diagnostics` as the last
  component plus a 6-arg delegating constructor so existing callers/tests compile.
- `core/api/DefaultAemCodeAnalyser.java` — accept a `Consumer<AnalysisDiagnostic>` (default no-op)
  and thread it into the scanners (constructor parameter or a small `DiagnosticSink` class).
- `core/export/ConsoleReportFormatter.java` — footer line "N files skipped (use --verbose)".
- `core/export/SarifReportExporter.java` — `invocations[0].toolExecutionNotifications` per
  diagnostic (level `note`/`warning`).
- `cli/AnalyserCli.java` + both Mojos — `--verbose` / `-D aem-analyser.verbose`.

Implementation steps:
1. Introduce `DiagnosticSink` (thread-safe `List` + `add(...)`), pass it to scanners; keep existing
   scanner constructors with a no-op default.
2. Replace each `catch` block listed above with `sink.add(...)`; never rethrow.
3. Add `diagnostics` to `AnalysisReport` last + keep a 6-arg constructor; update
   `withViolations` to preserve diagnostics.
4. Print a one-line summary always, and the full list only with `--verbose`.
5. Emit SARIF notifications; do not create results (they would be counted as findings).

Tests to add:
- Fixture with a syntactically invalid `.java` file: analysis succeeds, one `JAVA_PARSE_ERROR`
  diagnostic, and the rest of the fixture still produces its expected violations.
- Fixture with invalid JSON: one `CONFIG_PARSE_ERROR`, no exception.
- `AnalysisReportTest`: 6-arg constructor keeps an empty diagnostics list.
- SARIF test: `toolExecutionNotifications` present and `results` unchanged.

Acceptance criteria:
- No `catch` block in the listed classes silently discards an exception.
- Console output changes only by the added summary line (golden-file test).
- Public API stays source-compatible (`mvn -q verify` on the CLI/plugin modules).

Risks / notes:
- `AnalysisReport` is a record; adding a component changes the canonical constructor and
  `equals`/`toString`. Acceptable pre-1.0 but must be called out in `CHANGELOG.md` (O8).
- Keep diagnostic ordering deterministic (sort by kind, path, line) — O4.

---

## Implementation (wave 3)

Landed:
- `model/report/DiagnosticKind` and `AnalysisDiagnostic` records.
- `util/DiagnosticSink` (thread-safe, deterministic ordering, root-cause unwrapping).
- `AnalysisReport.diagnostics` with source-compatible 4-arg/6-arg constructors and
  `withDiagnostics`; `withViolations` preserves diagnostics.
- `JavaSourceParser.parseStrict` so syntax errors are reported instead of returning an empty file.
- Diagnostics wired into Java parsing, OSGi config parsing, dependency indexing, and the
  HTL/JCR/OSGi scanners (via overloads that keep the old API).
- Console footer, CLI `--verbose`, Maven `<verbose>` and SARIF `toolExecutionNotifications`.
- Tests: `AnalysisDiagnosticsTest`, `DiagnosticSinkTest`, `AnalysisReportTest`,
  `SarifReportExporterTest`, `ConsoleReportFormatterTest`.
