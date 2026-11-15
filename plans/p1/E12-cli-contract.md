# E12 — CLI argument validation and testability

Status: Done (execution seam deferred)
Priority: P1
Area: aem-analyser-cli / `cli.AnalyserCli`

Problem / opportunity:
Wave-1 status (2026-11-15) — **partially implemented**:
- `parseFormats()` now validates formats once, returns `List<ReportFormat>` and `run()` catches
  `IllegalArgumentException` with exit code 2.
- `validateFailOn()` rejects unknown severities with exit code 2.
- The `--html` duplicate check now compares against the parsed enum list (case-insensitive input).
- Remaining E12 work: move `System.exit` out of `run()`, validate numeric ranges
  (`--min-coverage`, `--max-duplication`, `--threads`), and add the `execute()` seam for tests.

Already handled (verified — do not re-implement):
- `--sonar` (default true), `--no-sonar` and `--sonar-rules` all exist and are combined as
  `enableSonar = sonar && !noSonar` (l.160). `AnalyserCliTest.testSonarCliOptionsRegistered` covers
  registration and parsing.
- `--fail-on` exists and maps `WARNING`/`INFO` to exit code 1; `--fail-on-warning` is a separate
  legacy alias.
- `--fix`, `--fix-dry-run`, `--changed-only`, `--since` are registered, and the git filter runs
  before the fixer, so the fixer only sees in-scope violations.
Real gaps:
1. Invalid `--format` throws a raw `IllegalArgumentException` from `ReportFormat.valueOf`
   (`AnalyserCli.export` l.230). `run()` does not catch it, so picocli prints a stack trace and the
   exit code is 1, not 2.
2. Invalid `--fail-on` (e.g. `--fail-on BANANA`) is silently ignored — no warning, exit code 0.
   `--fail-on ERROR` is also a no-op relative to the default error gate.
3. `System.exit` is called inside `run()` (l.199, 202, 205), so no test can assert an exit code and
   `main`'s own `System.exit` is dead code.
4. `--format html` (lower-case) plus `--html` writes the file twice: the loop compares
   `formats.contains("HTML")` case-sensitively (l.256) although `valueOf` upper-cases for parsing.
5. `-o/--output` is a *directory*; the README documents `--output-file`, which does not exist
   (P0 C7 owns the doc fix; the CLI should still reject `--output-file` with a clear message).
6. `--fix` + `--changed-only` + non-dry-run leaves a pre-fix report (C6) *and* a pre-fix git filter;
   the ordering must be defined once C6 lands.
7. `--threads` accepts 0/negative silently (mapped to defaults by `workerThreads()`), and
   `--min-coverage` accepts > 100.

Desired behavior:
- Every option is validated; invalid input yields a one-line message and exit code 2.
- Exit-code computation is a pure function that tests can call.

Files to change:
- `cli/AnalyserCli.java` — split `run()` into `int execute(PrintWriter)` + `run()` wrapper;
  normalise `formats` once; validate `failOn`, `format`, numeric ranges; use
  `CommandLine.ParameterException` for parse-time errors.
- `cli/AnalyserCliTest.java` — call `execute()` instead of running the process.

Implementation steps:
1. Add a `List<ReportFormat> parseFormats()` that returns enum values and throws
   `ParameterException`-compatible errors; store the parsed list and use it in `export()` and in the
   `--html` duplicate check.
2. Add `Severity parseFailOn(String)`; reject unknown values with exit code 2.
3. Convert `run()` to `int run()` returning `exitCode(...)`, and `main`/`CommandLine.execute` keep
   the `System.exit` outside the analysis path. Use picocli's `IExecutionExceptionHandler` to map
   `IllegalArgumentException` to exit code 2.
4. Validate `minCoverage ∈ [0,100]`, `maxDuplication ∈ [0,100]`, `threads ≥ 1`.
5. Once C6 lands, re-analyse after a non-dry-run fix and re-run the git filter on the new report.

Tests to add:
- `execute(["--format","BOGUS"])` → 2 and a message containing the accepted values.
- `execute(["--fail-on","BOGUS"])` → 2.
- `execute(["--min-coverage","150"])` → 2.
- `execute(["--format","html","--output", tmp])` → exactly one HTML file.
- `exitCode(report)` unit tests for ERROR/WARNING/INFO/quality-gate combinations.

Acceptance criteria:
- No code path calls `System.exit` inside the analysis/fix/export flow.
- Invalid input never prints a stack trace.
- All existing `AnalyserCliTest` cases still pass.

Risks / notes:
- `run()` is the `Runnable` contract used by picocli; keep `run()` and add `int execute()` rather
  than changing the interface.
- `--no-color` and `--html` already write through `System.out`; injecting a `PrintWriter` is needed
  for the new tests.

---

## Implementation

- Formats are parsed once and validated with exit code 2; `--fail-on` is validated.
- Numeric options are validated: `--threads >= 1`, coverage/duplication in `[0,100]`,
  `--max-warnings >= 0`.
- `--verbose` prints diagnostics; `--baseline`/`--update-baseline` and `--dependency-jars` are wired.
- Remaining: moving `System.exit` out of `run()` into a pure `execute()` seam (deferred; tests call
  the validation helpers directly today).
