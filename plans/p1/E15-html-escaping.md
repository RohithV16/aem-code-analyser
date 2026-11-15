# E15 — HTML report escaping, self-containment and size bound

Status: Done
Priority: P1
Area: aem-analyser-core / `export.HtmlReportExporter`

Problem / opportunity:
Already handled (verified — do not re-implement):
- Every interpolated violation value is escaped through `escape()` (l.463-470): `ruleId`,
  `filePath`, `region().startLine()`, `message`, `remediation`, related `filePath`, duplication class
  names, coverage `sourcePath`, scan-statistic keys/values, and `projectRoot`.
- The report is fully self-contained: inline `<style>` and inline `<script>`, no `<script src>`,
  no `<link href>`, no images, no fonts — verified by reading all append calls.
- Violations are sorted deterministically before rendering (severity → filePath → startLine).
Remaining gaps:
1. **Size**: `generateHtml` builds one `String` in a `StringBuilder(32768)` with no cap. 50k
   violations produces a multi-MB string held twice (builder + result) and a browser-hostile page.
2. **Non-reproducible output**: `Instant.now()` with the system zone (l.23-24, 166) makes byte-level
   output differ per run, which blocks golden-file tests and O4 determinism.
3. `escape()` escapes `& < > " '` only; `\r`/control characters from parsed messages are emitted
   raw inside HTML text. Low risk, trivial to add.
4. Fields already available but unused: `subtype` and `targetPid`. Adding them is safe only if they
   go through `escape()`.
5. No test asserts the "no external asset" property, so a future `<script src=…>` regresses silently.

Desired behavior:
- The document is safe for any violation text, byte-identical for the same input and clock, and
  bounded in size with an explicit truncation notice.
- The self-contained property is test-enforced.

Files to change:
- `core/export/HtmlReportExporter.java` — `Clock` injection, streaming `export(...)` that writes
  sections straight to the `Writer`, max-rows cap, control-char escaping, render `subtype`.
- `core/export/HtmlReportExporterTest.java` — escaping, self-containment, determinism, cap.

Implementation steps:
1. Add `HtmlReportExporter(Clock clock)` with a no-arg constructor delegating to `Clock.systemUTC()`
   (keeps current API).
2. Split `generateHtml` into `writeHead`, `writeKpis`, `writeViolations`, `writeTabsBase`,
   `writeScript`, each taking the `Writer`; keep `generateHtml` as a thin wrapper over a
   `StringWriter` for tests. Do this **after** adding a golden test against the current output, so
   the refactor is provably behaviour-preserving.
3. Cap rendered violations at `maxViolations` (default 5000, constructor-injectable): render the
   first N and a footer row "… N more violations omitted; use --format SARIF for the full set".
   Always render the KPI totals from the full report.
4. Escape `\r` and `\u0000-\u001F` as numeric references.
5. Add `data-subtype` using the already-escaped `subtype`.

Tests to add:
- `HtmlReportExporterTest.testEscapesAllViolationText`: message containing `<script>alert(1)</script>`,
  `"`, `'`, `&`, and a `filePath` with the same — assert none appears unescaped, and
  `&lt;script&gt;` does.
- `testNoExternalAssets`: assert the output contains no `src="http`, `href="http`, `<link`,
  `<img`, and no `@import`.
- `testDeterministicOutput`: two exports with a fixed `Clock` are `equals`.
- `testViolationCap`: 3 violations with `maxViolations=2` → 2 rows + notice + total "3".

Acceptance criteria:
- No test string survives unescaped anywhere in the output.
- Same input + same `Clock` ⇒ identical bytes.
- Page size stays bounded for a 100k-violation report (assert `length < maxViolations * 2 KB`).

Risks / notes:
- Streaming changes the `export(...)` write pattern but not the signature
  (`ReportExporter.export(AnalysisReport, Writer)`); `generateHtml` stays for compatibility.
- The existing `HtmlReportExporterTest` asserts raw substrings only, so it will keep passing.

---

## Implementation

- `HtmlReportExporter(Clock, maxViolations)` with a no-arg default; deterministic output for a fixed
  clock.
- Rendering is capped (default 5000 rows) with an explicit "N more violation(s) omitted" row; KPIs
  still reflect the full report.
- `escape()` now neutralises CR/LF and control characters in addition to HTML metacharacters.
- Tests: determinism, cap + totals, XSS/path escaping.
