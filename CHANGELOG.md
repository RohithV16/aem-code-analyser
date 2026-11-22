# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.

## [Unreleased]

### Added

- **First Maven Central release (1.0.10).** `io.github.rohithv16:aem-code-analyser`,
  `aem-analyser-core` and `aem-analyser-maven-plugin` are published with sources, javadoc and GPG
  signatures, so the plugin needs no repository entry and no token. Verified by resolving both the
  plugin goal and the library from an empty local repository. The shaded CLI stays a release asset.

### Added

- `-Prelease-central` actually works now: signed jars, sources and javadoc for the core library and
  the Maven plugin, published through the Sonatype Portal. Javadoc never built under the old profile
  (doclint rejects record components without `@param`), and the shaded CLI jar is excluded from the
  Central bundle because it bundles its own dependencies.

### Changed

- Maven Central publishing is a separate manual workflow (`publish-central.yml`, tag + optional dry
  run) instead of part of the release: Central has a publishing quota and a permanent public record,
  so a release only tags, publishes to GitHub Packages and attaches assets.

- README and the published site now lead with the easiest route — run it from a clone with nothing to
  install, then download the jar, then the Maven plugin — and the per-rule detail lives only in the
  generated rule catalogue (`docs/rules/`), which the site links to instead of repeating 30 rows on
  the landing page.

### Fixed

- `aem-analyser.failOn=NONE` is report-only in the Maven plugin, as it already was in the CLI: the
  severity gates are skipped and only `minCoverage`, `maxDuplication` and `maxWarnings` can still
  fail the build. Any other unsupported value is now rejected with the accepted set instead of being
  silently ignored. The failure message names the rule ids that actually fired instead of always
  blaming `AEM-DEAD`.
- **AEM-CLOUD-004 sees fully-qualified usage.** The rule filtered source lines with
  `startsWith("import ")`, so `com.day.cq.wcm.api.WCMMode.EDIT` written inline without an import was
  invisible and such a project passed the gate while still using the deprecated type. The rule now
  scans every line for a qualified reference (comments excluded, lookalike names ignored) and still
  reports an imported type once, at the import.

## [1.0.8] — 2026-11-16

### Changed

- **The project root is optional.** With no arguments the analyser uses the current directory and,
  when run inside a module, the reactor that owns it. Discovery walks past module POMs (a POM whose
  declared parent exists on disk is a module) and stops at an empty `<relativePath/>`, because that
  parent is resolved from a repository. Previously it stopped at the first POM, so running inside
  `bundle/` analysed that module alone and silently lost every cross-module finding.
- A positional project root is accepted: `aem-analyser .`.

### Added

- `scripts/run-from-source.sh` — run the analyser straight from a clone, rebuilding only when
  sources changed.
- Releases now attach the core and plugin jars **and their POMs**, so the plugin can be installed
  offline with `install:install-file` and needs no registry token.

### Fixed

- Documentation claimed the Maven plugin could be consumed from JitPack. It cannot: JitPack relocates
  the groupId and Maven rejects a plugin whose descriptor declares a different group. JitPack is
  documented for `aem-analyser-core` only.
- Removed the CI step that rewrote documented versions; it silently did nothing on the previous two
  releases. Snippets now use `${aem-analyser.version}` with a link to the releases page.

## [1.0.7] — 2026-11-16

### Added

- Release assets include `aem-analyser-core` and `aem-analyser-maven-plugin` (previously only the
  shaded CLI jar), with `SHA256SUMS.txt` covering every attached file.
- Release notes lead with the token-free consumption route; the GitHub Packages route states its
  `read:packages` requirement.

## [1.0.6] — 2026-11-16

### Fixed

- The release workflow's documented-version sync anchored on the development POM version instead of
  the newest tag, so it changed nothing (superseded in 1.0.8 by removing the rewrite entirely).

## [1.0.5] — 2026-11-16

### Fixed

- SARIF marks retired rule ids with `defaultConfiguration.enabled: false`, so consumers and the probe
  checker can distinguish a retired rule (`AEM-QUAL-006`) from one produced by another engine
  (`AEM-DUP-010`, PMD CPD). Without the distinction the new validation job demanded a probe for a
  rule that can never fire.

## [1.0.4] — 2026-11-16

### Changed

- Every rule delegates violation-path rendering to `RuleContext.relativePath(Path)`, replacing 27
  near-identical private helpers — the copies had drifted, and five never normalised Windows
  separators.
- Rule registration moved to `RuleCatalog`, which is validated against `RuleId` at startup: an
  unknown id, a duplicate producer, or an id with no producer now fails immediately instead of
  silently changing what a report contains.
- Retired `AemQual006Rule`; `AEM-QUAL-004` owns the check. The id remains for baselines.

### Added

- `validation/reactor` — a three-module project with pinned expected findings, covering injection
  through a wildcard import, a `@Reference` on a shared base class, an OSGi PID built from a constant
  in another class, a factory PID amendment and a cross-module clientlib reference.
- `scripts/run-reactor-validation.sh` and `scripts/check-rule-docs.sh`; both run in the new CI
  `validation` job alongside the rule probes, which had never run in CI before.
- Rule pages are now generated end to end (the retired/other-engine notes moved into the generator),
  so a stale page fails CI instead of shipping.

### Removed

- `plans/` (42 files) and `docs/AEM_CODE_ANALYSER_SPECIFICATION.md` — implemented work recorded in
  this changelog, and a 1374-line document that restated `PROJECT.md` and `docs/rules/`.

### Build

- Release versioning moved into `scripts/next-version.py`: a `-SNAPSHOT` publishes that version
  instead of incrementing again, main is left on the next `-SNAPSHOT`, and an existing tag fails
  unless `force=true`.

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
