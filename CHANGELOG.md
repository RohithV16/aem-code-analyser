# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.

## [Unreleased]

### Added

- HTML dashboard upgrades: sortable finding columns, group-by rule/file, deep links and per-finding
  permalinks, client-side CSV/JSON export, PR-ready Markdown summaries, a Run Health tab for parser
  diagnostics and baseline-accepted findings, persistent dark mode, a print stylesheet, keyboard
  navigation and a no-JavaScript fallback.
- Rule-probe validation harness: `validation/probes` (one deliberate positive case for all 30
  rules), `scripts/run-rule-probes.sh`, `scripts/inject-rule-probes.sh` and
  `scripts/check_rule_probes.py`. The checker fails when a rule is missing, is only demonstrated by
  non-probe code, is absent from `expected-rules.txt`, or has no probe at all.
- Structured analysis diagnostics (`AnalysisDiagnostic`, `DiagnosticSink`) surfaced on the console,
  via CLI `--verbose`, Maven `<verbose>` and SARIF `toolExecutionNotifications`.
- Baseline support: `--baseline`, `--update-baseline`, Maven `<baseline>`/`<updateBaseline>`, with a
  stable fingerprint that ignores line moves.
- Inline suppression directives: `aem-analyser-disable-next-line`, `aem-analyser-disable-file`.
- CLI `--ignore-config` and `--dependency-jars`; Maven `<ignoredConfigPaths>` and `<dependencyJars>`.
- Maven `formats`/`outputFile` on both goals, a shared `AbstractAnalyserMojo`, and a `<format>` alias.
- `LICENSE` (Apache-2.0), `<licenses>` metadata, `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md`.

### Fixed

- `AEM-DUP-002` primary and related locations are now deterministic: duplicate groups are ordered
  by path instead of filesystem discovery order.
- SARIF related locations are no longer double-wrapped in `physicalLocation`, and the whole document
  is byte-identical across runs (stable rule catalogue order and stable `ruleIndex` values).
- JavaParser upgraded to 3.28.2, so Java 21 constructs such as `var` inside record patterns are
  parsed instead of producing a `JAVA_PARSE_ERROR` and silently skipping the file.
- `ignoredConfigPaths` is now actually applied (reactor-relative globs).
- Runmode index stores each runmode individually.
- Hyphenated OSGi PIDs are accepted; ambiguous legacy factory splits are disambiguated against
  component PIDs.
- `AEM-DEAD-002A` unions all bound OCDs, is case-sensitive and no longer normalises config keys.
- Deleted the dead second producer of `AEM-DUP-010`.
- `--fix` re-analyses before writing reports and computing the exit code.
- Platform PID prefixes no longer match across package-segment boundaries.
- `.content.xml` PIDs no longer carry a bogus `.content` suffix.
- Reactor root is no longer scanned as a content module; duplicate findings are collapsed.
- Resource lifecycle false positives (`AEM-PERF-001/002`) for borrowed resolvers and sessions owned
  by a try-with-resources resolver.
- `AEM-CLOUD-003` now detects `getServiceResourceResolver(null)` with no bundle-level mapping.
- AEM-aware Sonar suppressions for Metatype reserved names, servlet lifecycle no-ops and JCR node
  literals.
- `@Component(service = {})` no longer inherits implemented interfaces; explicit service lists win.
- `AEM-DEAD-003C` skips factory, disabled, programmatically activated and `REQUIRE` components.
- Method-level and constructor-parameter `@Reference` injections are captured.
- Git changed-file resolution handles renames, untracked directories, spaces/unicode and
  merge-base `--since` diffs.
- SARIF uses the real build version, correct `informationUri`, `helpUri` and valid regions.

## [1.0.0] - 2026-11-15

Initial public snapshot of the Phase 1 cross-boundary dead-code analyser.

### Fixed (after 1.0.0 snapshot)

- `AEM-CLOUD-003` no longer reports an ERROR when `getServiceResourceResolver` receives an unresolved
  Java constant instead of a literal subservice; `Constants.FIELD` constants declared in the same
  file now resolve to their value.
- Removed example-project-specific wording from production code.

### Fixed (genericity / false positives)

- `AEM-CLOUD-003` no longer treats an unevaluable map variable as an explicit `null` subservice, and
  fails open when mapper configuration exists but cannot be matched per bundle.
- `AEM-PERF-002` treats sessions adapted from borrowed resolvers (parameters, injected fields,
  request/asset resolvers) as borrowed views, not leaks.
- `AEM-DEAD-003B` recognises Sling Models consumed through `@Self`/`@ChildResource` injection,
  superclasses, interfaces and return types, and ignores nested injection DTOs.
- `AEM-DEAD-004` indexes `.java` files inside content packages (`jcr_root`).
- `AEM-DUP-010` rejects same-file overlapping windows, delegation-only blocks, and reports both
  file paths.
- `AEM-CLOUD-001` downgrades `config.local` findings to WARNING; `AEM-SEC-001` downgrades
  `data-sly-element` unsafe contexts to WARNING.
- Generalised report wording: no invented secret names, no internal Sonar check class names, no
  "BUILD FAILURE" for a gate scan, `Target:` instead of `Target PID:`.

- Service-resolver analysis is API-shape independent: factory `getResourceResolver(Map)`,
  `getServiceResourceResolver`, repository `loginService`/`login`, generic auth-map construction
  (`Map.ofEntries`, `entry`, `put`, wrappers) and receiver-type ownership.
- Administrative-access detection covers generic `login`/resolver calls using `admin`/`anonymous`
  credentials, not only two deprecated method names.
- `AEM-QUAL-003` detects OCD-style scheduler properties and `Scheduler`/`ScheduleOptions` fields.

### Added

- HTML duplication tab now lists individual duplicate block pairs with kind, both
  `path:start-end` locations, line/token counts, and a source preview.

### Added (enhancement wave)

- Rule documentation generator (`--generate-rule-docs`) and 30 generated pages under `docs/rules/`.
- Enforcer/reproducible build, `quality`, `run-its`, `release-central` and `api-check` profiles.
- SARIF/Sonar schema-conformance tests, CI OS matrix and Maven Invoker IT.
- CLI `execute()` seam, `--max-file-size`, `--timeout`, `--config`, `--baseline-report`,
  `--history`, `--history-limit`.
- `.aem-analyser.yml` project configuration, per-bundle service-user mappings, changed-file
  prefilter, runmode precedence resolver.
- Sling Models `@Via`/`@Exporter` and multiline HTL `data-sly-use`.
- Duplicate-block fingerprints and comment-aware code-line counts.
- New rules: `AEM-DEAD-005`, `AEM-SEC-002`, `AEM-PERF-004`, `AEM-QUAL-006`, `AEM-CLOUD-004/005`.
- Run-history trend table in the HTML report.

### Changed

- HTML dashboard is generated on every CLI/Maven run by default; disable with `--no-html` or
  `<generateHtml>false</generateHtml>`.
- Maven gained parity flags for changed-only/since, fixes, quality gates, history and baseline
  reporting; parameter aliases `maxFileSize` and `timeout` added.
- GitHub Pages site under `docs/` (theme, landing page, 30 rule pages) with a Pages deployment
  workflow.

### Added

- Deliberate rule-probe reactor (`validation/probes`) covering all 29 rules, plus
  `scripts/run-rule-probes.sh` and `scripts/inject-rule-probes.sh` for safe validation against a
  temporary copy of any AEM repository.
- Fixed Sling Models `resourceType` parsing from `@Model(resourceType=...)` and fully-qualified
  interface/superclass resolution (surfaced by the probes).
