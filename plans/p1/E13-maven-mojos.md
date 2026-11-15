# E13 — Maven parameter parity and Invoker ITs

Status: Done (reflection guard instead of Invoker)
Priority: P1
Area: aem-analyser-maven-plugin / `maven.CheckMojo`, `maven.ReportMojo`

Problem / opportunity:
Wave-1 status (2026-11-15) — **mostly implemented**:
- `AbstractAnalyserMojo` now exists and is extended by both goals. It owns `projectRoot`, `skip`,
  `profiles`, `threads`, `ignoredServices`, `ignoredConfigPaths`, `enableSonar`, `sonarRules`,
  `formats`, `outputFile`, `executionRoot`, `project`, `session`.
- `check` now accepts `formats`/`outputFile`, writes reports **before** the fail gate and validates
  formats with `MojoFailureException`.
- `report` shares the same surface and writes UTF-8.
- Remaining E13 work: Maven Invoker ITs, a `<format>` singular alias, and mapping
  `session.getRequest().getActiveProfiles()` into the context.

Already handled (verified — do not re-implement):
- `aggregator = true` plus an execution-root guard, so the goal runs once per reactor.
- `aem-analyser.skip`, `aem-analyser.projectRoot`, `failOnError`, `failOnWarning` on `check`.
- `ReportMojo` already has `aem-analyser.formats` (comma-separated `String`) and writes
  `aem-analyser.sarif`, `sonar-aem-analyser-report.json`, `aem-analyser-report.txt`,
  `aem-analyser-report.html`.
Real gaps:
1. **`CheckMojo` has no `formats`/`outputFile` at all** — the README's `<format>` and `<outputFile>`
   are unknown parameters and make the build fail with "Unknown parameter".
2. No `profiles`, `threads`, `ignoredServices`, `ignoredConfigPaths`, `enableSonar`, `sonarRules` on
   either Mojo, so Maven users cannot reproduce CLI behaviour.
3. `ReportMojo.writeReports` uses `Files.newBufferedWriter(file)` with the platform default charset,
   while the CLI writes UTF-8. On Windows this corrupts non-ASCII messages.
4. `ReportFormat.valueOf(format.toUpperCase(...))` is unguarded → `IllegalArgumentException` escapes
   as a generic `MojoExecutionException` with no actionable message.
5. `ReportMojo` has no `failOn*`; `CheckMojo` has no report output, so CI must run both goals.
6. No Maven Invoker IT: nothing proves the documented `<configuration>` block works.
7. `ReportMojo.outputDirectory` is always `project.build.directory` of the *root* project; there is
   no `outputFile` override.

Desired behavior:
- Both goals expose the same parameter set, validated, with UTF-8 output.
- A documented configuration block is executed by an IT.

Files to change:
- `maven/AbstractAnalyserMojo.java` (new) — shared parameters, `buildContext()`, format parsing,
  `MojoFailureException` on invalid input.
- `maven/CheckMojo.java`, `maven/ReportMojo.java` — extend the base, delete duplicated code.
- `maven-plugin/src/test/resources/it/*` (new) — Invoker projects.
- `aem-analyser-maven-plugin/pom.xml` — `maven-invoker-plugin` with `invoker.properties`.
- `README.md` — align with the real parameter names (coordinate with C7).

Implementation steps:
1. Create the abstract base with: `projectRoot`, `skip`, `profiles` (`List<String>`), `threads`,
   `ignoredServices`, `ignoredConfigPaths`, `enableSonar` (default true), `sonarRules`,
   `formats` (`List<String>`, default `SARIF,SONAR`), `outputFile` (`File`, nullable),
   `failOnError`, `failOnWarning`, `failOn` (`String`).
2. `buildContext()` maps them to `AnalysisContext.builder(...)`; pass
   `session.getRequest().getActiveProfiles()` as the profile default so E8 gets real data.
3. Parse formats into `ReportFormat` in the base; on failure throw
   `MojoFailureException("Unsupported format 'X'. Supported: CONSOLE, SARIF, SONAR, HTML")`.
4. `ReportMojo.writeReports`: `Files.newBufferedWriter(file, StandardCharsets.UTF_8)`; honour
   `outputFile` for single-format runs and reject it when more than one format is requested.
5. Add `formats`/`outputFile` to `check` (write reports, then apply the fail gate to the same report).
6. Add Invoker ITs: `it-basic` (check passes), `it-fail-on-error` (expects failure), `it-report-html`
   (asserts the HTML file exists), `it-invalid-format` (asserts the message and `MojoFailureException`).

Tests to add:
- Invoker ITs above (`mvn -Prun-its verify`).
- A plain JUnit test for format parsing and for `outputFile` + multi-format rejection.

Acceptance criteria:
- The README configuration block (`<formats>`, `<outputFile>`) builds successfully.
- `aem-analyser:check` produces the configured reports and then fails/succeeds per the fail gate.
- Non-ASCII violation text survives a Windows default-charset run (assert by reading with UTF-8).

Risks / notes:
- Keep the existing property names (`aem-analyser.formats`, singular `<format>` accepted as an alias
  via `@Parameter(alias = "format")`) so already-published docs do not break twice.
- Report writing happens before the fail gate on purpose: CI needs the SARIF even on failure.

---

## Implementation

- Shared `AbstractAnalyserMojo` owns the full surface; both goals extend it.
- `<format>` is accepted as an alias of `<formats>`; invalid formats fail with a clear message.
- `formats`/`outputFile` work on `check` (reports written before the fail gate).
- `MojoParameterTest` guards parameter parity and base-class inheritance. A full Maven Invoker IT
  remains a follow-up (needs a dedicated IT fixture reactor).
