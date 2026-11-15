# E10 — SARIF and Sonar exporter conformance

Status: Verified
Priority: P1
Area: aem-analyser-core / `export.SarifReportExporter`, `export.SonarReportExporter`

Problem / opportunity:
Wave-2 status (2026-11-15) — **metadata and region fixes implemented**:
- `VersionInfo` reads the filtered `version.properties` (Maven build) and falls back to the manifest.
- `informationUri` points at this project; rule definitions carry `helpUri`.
- Single-line regions are widened so `endColumn > startColumn`.
- Remaining E10 work: full schema validation tests, registry-driven Sonar `type`/`effortMinutes`,
  modern Sonar `cleanCodeAttribute`/`impacts`, and URI escaping for paths with spaces.

Already handled (verified — do not re-implement):
- SARIF 2.1.0 shape with `$schema`, `version`, one run, `tool.driver`, 0-based `ruleIndex`,
  `%SRCROOT%` `originalUriBaseIds`, 1-based coordinates and a synthetic rule definition for unknown
  rule ids.
- Sonar `textRange` already guarantees 0-based columns and `endColumn > startColumn` when
  `startLine == endLine` (`SonarReportExporter.textRange`, l.74-90).
- `TextRegion.forWholeFile()` is `(1,1,1,2)`, which is valid for both formats.
Real gaps:
1. SARIF `informationUri` is `https://github.com/aem-analyser/aem-code-analyser` — not this project
   (`https://github.com/rohithv16/aem-code-analyser`).
2. `tool.driver.version` is hardcoded `"1.0.0"` instead of the build version.
3. No `helpUri`, no `fullDescription`, no `properties.tags`, no `security-severity`/CWE.
4. `region` is emitted verbatim; `TextRegion` allows `endColumn == startColumn`, which is invalid
   SARIF when `startLine == endLine`.
5. Violation `subtype`/`message` are the only rule metadata; `type` and `effortMinutes` are hardcoded
   `switch`es in `SonarReportExporter` that duplicate `RuleId` knowledge (blocks F3).
6. Sonar's modern schema uses `cleanCodeAttribute` + `impacts` (`severity`/`type` are deprecated);
   neither is emitted.
7. `artifactLocation.uri` is a raw path — spaces/unicode are not URI-escaped.
8. No schema validation in tests; SARIF is only round-tripped by eyeball.

Desired behavior:
- Both documents validate against their published schemas.
- All rule metadata comes from one registry (F3), not from exporter-local switches.

Files to change:
- `core/export/SarifReportExporter.java` — build-version supplier, correct `informationUri`,
  `helpUri`, `fullDescription`, region normalisation, URI escaping.
- `core/export/SonarReportExporter.java` — registry-driven `type`/`effortMinutes`, add
  `cleanCodeAttribute` + `impacts` alongside legacy fields.
- `core/export/VersionInfo.java` (new) — `Implementation-Version` from the CLI/jar manifest with a
  `"dev"` fallback.
- `pom.xml` / `aem-analyser-cli/pom.xml` — ensure the manifest carries the version.

Implementation steps:
1. Add `VersionInfo.current()` reading `Package.getImplementationVersion()` for the exporter class,
   falling back to a generated properties resource (`maven-resources-plugin` filtering on
   `${project.version}`).
2. Normalise regions once (`endColumn = max(startColumn + 1, endColumn)`) in the SARIF location
   helper; add the same guard for `endLine == startLine`.
3. Emit `helpUri` = `<repo>/blob/main/docs/rules/<RULE-ID>.md` once those docs exist; until then
   point at the README rule table and record a TODO in F3.
4. De-duplicate the switches by introducing a minimal `RuleDescriptor` (id, type, effort, tags,
   helpUri) as an interim step; F3 replaces it with the full registry.
5. URI-escape path segments with `URI`/`Paths` rather than string concatenation.

Tests to add:
- `SarifReportExporterTest` (new): validate against the vendored SARIF 2.1.0 JSON schema
  (`everit-org/json-schema` or `networknt`), assert `informationUri`, `version != "1.0.0"`,
  `ruleIndex` consistency, and `endColumn > startColumn`.
- `SonarReportExporterTest` (new): schema-shape assertions, `cleanCodeAttribute`/`impacts` present,
  zero-based columns, path with spaces.
- Golden-file test for a 3-violation report (stable key order).

Acceptance criteria:
- Both exporters validate against their schema in CI.
- `tool.driver.version` equals `project.version` in a packaged build.
- A path containing a space produces a valid SARIF `uri`.

Risks / notes:
- Adding `impacts` while keeping `severity`/`type` is required for compatibility with older Sonar
  versions; do not remove the legacy keys until SonarQube < 10.2 support is dropped.
- Schema files must be vendored as test resources (no network in CI).
