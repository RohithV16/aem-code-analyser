# F1 — Suppression and baseline

Status: Done (MVP, 2026-11-15)
Priority: P2
Area: aem-analyser-core / new `core/baseline`; `api.DefaultAemCodeAnalyser`; CLI + both Mojos

Problem / opportunity: Every adoption starts with "we already have 400 findings". There is no way to
freeze current findings, and no inline suppression, so teams either fail the build or ignore it.
`AnalysisContext.ignoredServices`/`ignoredConfigPaths` exist but are coarse and unused (C1).

Desired behavior:
- `.aem-analyser-baseline.json` records accepted findings by fingerprint; matching findings are
  filtered out of the report (or downgraded to INFO with `--baseline-report`).
- `// aem-analyser-disable-next-line AEM-DEAD-001` / `disable-line` / block
  `aem-analyser-disable` … `aem-analyser-enable` work in `.java`, `.html` (HTL comments) and XML
  (`.content.xml`) comment syntax.
- `--update-baseline` rewrites the file; `--baseline <path>` selects an alternative file.

Data model:
- `BaselineEntry(String fingerprint, String ruleId, String filePath, String subtype, String messageHash,
  String toolVersion, Instant recordedAt)`.
- `Baseline(String schemaVersion, String toolVersion, List<BaselineEntry>)` — JSON via Jackson,
  stable key order, LF endings.
- Fingerprint = `sha256(ruleId | subtype | filePath | normalizedMessage | targetPid)` truncated to 16
  hex chars, where `normalizedMessage` replaces digits and quoted names with `#` so line shifts and
  counts do not invalidate it. Line numbers are deliberately excluded; `filePath` + `subtype` keep it
  precise enough.
- `SuppressionIndex`: per-file `Map<Integer, Set<String>>` (line → rule ids, `*` = all) plus
  file-level `Set<String>`, built during the parse phase so it is available before rules run.
- `BaselineFilter`: `AnalysisReport filter(AnalysisReport, Baseline)`.

API surface:
- `AnalysisContext` gains `Optional<Path> baseline`, `boolean updateBaseline`, `SuppressionMode`.
- `AnalysisReport` gains counts (`suppressedCount`, `baselineSuppressedCount`) so reports stay honest.
- New `BaselineStore.load/save(Path)`.

CLI / Maven flags: `--baseline <file>`, `--update-baseline`, `--suppress-inline/--no-suppress-inline`,
`--baseline-report`; Mojo `<baseline>`, `<updateBaseline>`, `<suppressInline>`.

Test strategy: fingerprint stability tests (line move, message count change); inline suppression in
each of the three comment syntaxes incl. a suppression on the wrong line; baseline round-trip;
"update then filter ⇒ zero findings"; JSON golden file; a fixture proving suppressed findings still
appear as counts.

Acceptance criteria: A repo with a committed baseline exits 0; the same run with `--update-baseline`
exits 0 and produces a diff-free second run; inline suppression never hides a different rule.

Risks / notes: Fingerprints must be stable across versions or baselines churn — bump
`schemaVersion` instead of silently changing the hash. Suppression parsing must not run on
unparseable files (E11).

Implementation (MVP, wave 5):
- `core/baseline/Fingerprint` (SHA-256 of rule|subtype|path|normalised message, 16 hex chars).
- `BaselineEntry` / `Baseline` / `BaselineStore` (JSON, schema version, tool version).
- `BaselineFilter` removes accepted findings.
- `InlineSuppressions` supports `aem-analyser-disable-next-line`, `aem-analyser-disable-file` and
  wildcard (no rule ids / `*`) directives in Java, HTL and XML comments.
- CLI `--baseline`, `--update-baseline`; Maven `<baseline>`, `<updateBaseline>`.
- Tests: `BaselineStoreTest`, `InlineSuppressionsTest`; validated end-to-end on the portfolio repo
  (update → exit 0, reuse → 0 reported results).
- Not yet implemented: `--baseline-report`, suppressed counts on `AnalysisReport`, and
  `.aem-analyser.yml`.

# F2 — Project configuration `.aem-analyser.yml`

Status: Proposed
Priority: P2
Area: aem-analyser-core / new `core/config`; CLI + both Mojos

Problem / opportunity: Rule enablement, severity and thresholds can only be changed per-invocation
via flags, and `ignoredServices`/`ignoredConfigPaths` are the only allowlists. Multi-module teams
need a committed configuration file.

Desired behavior: A `.aem-analyser.yml` at the reactor root is discovered automatically and applied
after CLI/Maven flags (flags win), with a clear precedence chain
`defaults < file < CLI/Maven < inline suppression`.

Data model: `AnalyserConfig(int schemaVersion, Map<String, RuleConfig> rules, QualityGate gate,
Allowlists allowlists)`, `RuleConfig(boolean enabled, Severity severity, Map<String,Object> options)`,
`QualityGate(Double minCoverage, Double maxDuplication, Integer maxWarnings)`,
`Allowlists(Set<String> services, Set<String> configPaths, Set<String> pids, Set<String> packages)`.
Loader: Jackson YAML with strict unknown-key rejection and a JSON-schema file shipped in `docs/`.

API surface: `AnalyserConfigLoader.load(Path)`, `AnalysisContext.config` (optional), rule dispatch
consults `rules.get(id).enabled()` and maps severity through a `Violation.withSeverity(...)` helper
(missing today — add it).

CLI / Maven flags: `--config <file>`, `--no-config`; Mojo `<configFile>`, `<skipConfig>`.

Test strategy: precedence matrix (file vs flag, both directions); unknown key → actionable error;
disabled rule produces zero findings; severity override reflected in SARIF `level`/Sonar `severity`;
allowlist glob tests; YAML schema validation test.

Acceptance criteria: The same repo analysed with and without `.aem-analyser.yml` differs exactly as
configured, and every invalid file fails with a message naming the key and line.

Risks / notes: Adding `Violation.withSeverity` must update baseline fingerprints only if severity is
part of the fingerprint — keep severity *out* of the fingerprint. Maven plugin must not silently read
a config from a parent directory when `projectRoot` is explicit.

# F3 — Central rule metadata registry

Status: Done (2026-11-15)
Priority: P2
Area: aem-analyser-core / `core/rules/metadata`; exporters; CLI

Problem / opportunity: Rule metadata is spread across `RuleId` (id/name/description/severity),
`SonarReportExporter.issueType`/`effortMinutes` switches, `HtmlReportExporter.getRuleCategory`, SARIF
`defaultConfiguration`, and the README. They already drift.

Desired behavior: One `RuleDescriptor` per rule id, consumed by every exporter and by
`--list-rules` / `--explain <RULE>`.

Data model: `RuleDescriptor(String id, String name, Severity defaultSeverity, String shortDescription,
String fullDescription, String helpUri, List<String> tags, String remediation, String cwe,
RuleType type, int effortMinutes, boolean enabledByDefault, List<String> subtypes)`.
Source of truth: a Java enum/registry next to `RuleId` (reflection-free, compile-checked) plus one
Markdown page per rule under `docs/rules/<ID>.md` for `fullDescription`/`helpUri`.

API surface: `RuleRegistry.get(String id)`, `RuleRegistry.all()`, `RuleRegistry.forCategory(String)`,
`RuleRegistry.subtypes(String id)`. `RuleId` keeps its accessors and delegates.

CLI / Maven flags: `--list-rules`, `--list-rules --format json`, `--explain AEM-DEAD-002A` (prints
description, subtypes, remediation, docs path).

Test strategy: exhaustiveness test (`RuleId.values()` ⊆ registry, and every registered id appears in
`DefaultAemCodeAnalyser.rules`); every rule has a non-blank `fullDescription` and an existing
`docs/rules/<ID>.md`; SARIF `rules[]` length equals registry size; Sonar type/effort come from the
registry (no switches left).

Acceptance criteria: `grep -r "AEM-DEAD-00" --include=*.java` finds no hardcoded id outside the
registry, tests and rule implementations.

Risks / notes: `AEM-DUP-010` has two possible producers (C5) — register it once. Keep `RuleId` as the
public enum to avoid breaking the API.

# F4 — Incremental analysis cache

Status: Proposed
Priority: P2
Area: aem-analyser-core / new `core/cache`; `api.DefaultAemCodeAnalyser`

Problem / opportunity: `--changed-only` filters findings *after* a full analysis, so it is not a fast
path. Every run re-parses every file, re-runs CPD and SonarJava.

Desired behavior: Unchanged files reuse cached per-file results; the cache is keyed by content hash
and invalidated by tool version, rule-set hash, config hash and JDK/SonarJava version. A cold run
produces the same results as a warm run.

Data model: `CacheKey(String sha256, String toolVersion, String ruleSetHash, String configHash)`;
`FileCacheEntry(CacheKey, List<Violation> violations, List<ConfigPropertyEntry> configProperties,
List<PendingConsumption> consumptions, List<PendingServiceInjection> injections,
long sourceLines)`; one JSON file per module under `.aem-analyser-cache/` plus an index. Only
phase-A parse products are cached — the graph and rules are cheap and stay in memory.

API surface: `AnalysisCache.open(Path, CacheKey)`, `get/modify(Path file, long size, long mtime)`;
`DefaultAemCodeAnalyser` consults it inside `parseJavaSourcesParallel` and `parseOsgiConfigs`.
`--cache-dir`, `--no-cache`, `--clean-cache`.

CLI / Maven flags: `--cache`, `--no-cache` (default off for CLI single runs, on for Maven reactor),
`--cache-dir <dir>`; Mojo `<cache>`, `<cacheDir>`.

Test strategy: cold vs warm equality (violations + metrics); invalidation on file change, on version
bump, on rule-set change; corrupted cache is ignored (diagnostic, not failure); cache dir excluded
from scanning (`HtlScanner`/`JcrComponentScanner` ignore lists).

Acceptance criteria: Warm run on an unchanged reactor is ≥ 3× faster on the portfolio repo and
produces byte-identical SARIF.

Risks / notes: Caching `Violation` requires a stable JSON codec; keep cache entries versioned and
discard on any decode error. Never cache across different `projectRoot`s. Do not cache SonarJava
results separately from the parse products unless they are re-keyed (they depend on rule filters).

# F5 — CI annotations and platform formats

Status: Done (2026-11-15)
Priority: P2
Area: aem-analyser-core / `export`; `.github/workflows`

Problem / opportunity: SARIF/Sonar exist, but GitHub annotations, GitLab Code Quality and Azure DevOps
need different shapes, and there is no self-scan SARIF upload in this repo's CI.

Desired behavior: `--format GITHUB` (workflow commands), `GITLAB` (Code Quality JSON), `AZURE`
(##vso logging commands), plus SARIF upload for this repository's own CI.

Data model: new `ReportFormat` values `GITHUB`, `GITLAB`, `AZURE`; a shared `AnnotationSink`
(severity, file, line, message, ruleId) with three renderers. GitLab needs
`fingerprint`/`severity`/`location.path`; GitHub needs `::error file=…,line=…,title=…::msg` with
escaping of `%`, `\r`, `\n`.

API surface: `GitHubAnnotationExporter`, `GitLabCodeQualityExporter`, `AzurePipelinesExporter`
implementing `ReportExporter`.

CLI / Maven flags: extend `--format` and `<formats>`; `--annotations-on <ERROR|WARNING|INFO>`
threshold; no new Maven parameters beyond `formats`.

Test strategy: golden files per renderer; escaping tests (`%`, newline, comma in file names); a
GitLab schema assertion; an end-to-end run that prints annotations to a captured writer.

Acceptance criteria: This repo's CI uploads SARIF and annotates the PR from its own self-scan without
failing on pre-existing findings (requires F1).

Risks / notes: GitHub annotations cap at 10 per step and 50 total; batch by severity and emit a
summary line. Never emit annotations for suppressed/baseline findings.

# F6 — Safer auto-fixers

Status: Proposed
Priority: P2
Area: aem-analyser-core / `fix.AutoFixEngine`

Problem / opportunity: `AutoFixEngine` does whole-file regex edits
(`fixRedundantBoolean` l.118-128, `fixStandardCharsets` l.130-158). `replaceAll("\\s*==\\s*true\\b","")`
also rewrites comments and string literals, the output is written non-atomically, encoding is forced
to UTF-8, and there is no diff preview or idempotency guarantee.

Desired behavior: AST-anchored edits with byte-range replacement, atomic writes, encoding/BOM
preservation, idempotency (`fix(fix(x)) == fix(x)`), and a human-readable unified diff preview.

Data model: `Fix(int startOffset, int endOffset, String replacement, String ruleId, String description)`
produced by per-rule `FixProvider`s; `FixPlan(Path file, List<Fix>, Charset, boolean hadBom)`;
`FixSummary(files, fixes, records, diff, dryRun)`.

API surface: `FixEngine.plan(AnalysisReport) → List<FixPlan>`, `FixEngine.apply(List<FixPlan>)`,
`FixEngine.renderDiff(List<FixPlan>)`. JavaParser `LexicalPreservingPrinter` for Java;
`FixProvider` SPI so F9/new rules can contribute.

CLI / Maven flags: `--fix`, `--fix-dry-run` (existing), `--fix-diff`, `--fix-rules <ids>`,
`--fix-encoding <charset>`; Mojo `<fix>`, `<fixDryRun>` (new).

Test strategy: property-style idempotency test; a fixture where `== true` appears inside a string and
a comment (must not change); CRLF file preserved; BOM preserved; ISO-8859-1 file not corrupted;
atomic-write failure leaves the original intact; apply is skipped when the file changed since the plan.

Acceptance criteria: `--fix` on a fixture produces exactly the expected bytes, a valid compile, and a
second run reports "no fixes".

Risks / notes: Keep the current three S-rules as the only enabled providers initially; land C6
(re-analyse after fix) first, otherwise the report stays stale.

# F7 — Cross-boundary expansion

Status: Proposed
Priority: P2
Area: aem-analyser-core / `graph`, `parser`, `discovery`; new rules

Problem / opportunity: Phase 1 stops at Java/OSGi/HTL. AEM projects also break at the
HTL ↔ model/dialog boundary and in clientlib dependency graphs.

Desired behavior (incremental slices):
1. **HTL property binding**: `data-sly-use`-declared variables and `model.foo` / `${model.foo}`
   accesses map to `@Model` getters; unused getters become `AEM-DEAD-002B`-style findings.
2. **Dialog binding**: Granite `_cq_dialog/.content.xml` fields vs `@ValueMapValue` names —
   dialog fields with no consumer and consumers with no field.
3. **Clientlibs**: `categories`/`dependencies`/`embed` graph; orphan clientlibs (no `categories`
   referenced by HTL/`cq:includeClientLib`); cycles.
4. **Repoinit/service-user consistency**: extend `AEM-CLOUD-003` to cross-check
   `org.apache.sling.serviceusermapping` configs against repoinit system users.

Data model: `HtlExpression(path, variable, property, line, col)`; `DialogField(name, resourceType,
type, required)`; `ClientlibNode(categories, dependencies, embed, path)`; extends
`ReferenceGraph` with `HTL_READS_PROPERTY` and `CLIENTLIB_DEPENDS_ON` edge types.

API surface: new scanners (`HtlExpressionScanner`, `GraniteDialogScanner`, `ClientlibScanner`) and
new rules behind `RuleDescriptor` (F3). All new rule ids must be added to `RuleId` and to
`DefaultAemCodeAnalyser.rules`.

CLI / Maven flags: `--enable-rule <id>` / `--disable-rule <id>` (from F2), no dedicated flags.

Test strategy: golden fixtures per slice (positive + negative), one end-to-end fixture combining a
model, an HTL template and a dialog; FP regression fixtures for dynamic HTL expressions.

Acceptance criteria: Each slice ships with ≥ 3 TP and ≥ 3 FP fixtures and no new FP on the portfolio
baseline.

Risks / notes: Slice 1 needs a real HTL expression parser; keep it regex+offset based and treat
unresolvable expressions as "used" (F7 must never increase FPs).

# F8 — AEMaaCS governance checks

Status: Proposed
Priority: P2
Area: aem-analyser-core / `discovery.CloudConfigDiscoveryEngine` + new `core/cloud` validators

Problem / opportunity: `CloudConfigDiscoveryEngine` finds cloud config files but only
`AEM-CLOUD-001/002/003` interpret them. CDN/WAF/rate-limit YAML, dispatcher rules, log forwarding,
secret references and runmode-folder sanity are unchecked.

Desired behavior: A `CloudGovernanceValidator` suite producing `AEM-CLOUD-004..00N` findings:
- CDN/WAF/rate-limit YAML: unknown keys, obviously permissive origin rules, missing required
  sections (validate against a vendored JSON schema per config type).
- Dispatcher: `filters.any` allow-alls, missing `/filter` rules for known-sensitive paths, empty
  `statfiles`.
- Log forwarding: unknown appender/logger references, secret-looking literals.
- Secrets: `$[secret:X]` referenced but never documented in `README`/`vars`, plaintext twins.
- Runmode folders: `config.<runmode>` tokens that are not valid runmodes, duplicate `config` and
  `config.all` semantics.

Data model: `CloudFinding(ruleId, severity, schemaPath, jsonPointer, message, remediation)`;
`CloudConfigDescriptor` gains `type` and a parsed `JsonNode`/`Document`.

API surface: `CloudGovernanceValidator.validate(List<CloudConfigDescriptor>, RuleContext)`.

CLI / Maven flags: none new; gated by F2 rule enable/disable.

Test strategy: one YAML/JSON/dispatcher fixture per finding kind; schema-validation tests; a
"known-good" AEMaaCS project fixture that produces zero findings.

Acceptance criteria: Zero findings on a clean archetype; every finding points at a JSON pointer or
line; no network access.

Risks / notes: Schemas change across Cloud Manager releases — vendor them under
`src/main/resources/schemas/` with a version stamp and fail loudly on unknown `kind`.

# F9 — New rules

Status: Proposed
Priority: P2
Area: aem-analyser-core / `rules.impl` + `RuleId` + `RuleRegistry`

Problem / opportunity: Four high-value gaps remain: SSRF/open redirect, unbounded Oak queries,
resolver/session misuse, deprecated AEM APIs.

Desired behavior (one plan-sized slice each, all using F3 metadata and F6 fixers where safe):
- `AEM-SEC-002` SSRF/open redirect: `response.sendRedirect(request.getParameter(...))`,
  `URL`/`HttpClient` construction from request-derived strings, missing allowlist validation.
- `AEM-PERF-004` unbounded query: extend `QueryTraversalFinding` with "no `p.limit`/`p.hits`" and
  "query built from concatenated user input".
- `AEM-QUAL-006` resolver/session misuse: `ResourceResolver` stored in a field, `adaptTo(Session.class)`
  without `logout()`, `isLiveCycle` misuse.
- `AEM-CLOUD-004` deprecated API usage: a curated table (`com.day.cq.wcm.api.WCMMode` setters,
  `com.day.cq.dam.api.Asset#getMetadata` legacy forms, `org.apache.commons.lang.StringUtils`)
  mapped to replacements.

Data model: reuse `Violation`; each rule gets a `RuleDescriptor` (F3), a `remediation` string and a
`helpUri`. Deprecated-API table lives in one resource file, not in code.

API surface: four `RuleEvaluator`s registered in `DefaultAemCodeAnalyser.rules`; ids added to
`RuleId`; subtypes enumerated in the descriptor.

CLI / Maven flags: none new.

Test strategy: per rule ≥ 3 TP and ≥ 3 FP fixtures; `EndToEndAnalysisTest` extension; a
`docs/rules/<ID>.md` per rule; baseline run to confirm no FP on the portfolio.

Acceptance criteria: All four rules ship with docs, severity, effort and no FP on the baseline.

Risks / notes: SSRF detection is heuristic — default to `WARNING` and require a request-derived
source, never flag constant URLs. Do not implement these before F3 (metadata) and F1 (baseline),
otherwise adoption spikes.

# F10 — HTML trend/history dashboard

Status: Proposed
Priority: P2
Area: aem-analyser-core / `export.HtmlReportExporter`; new `core/history`

Problem / opportunity: The HTML report is a single-run snapshot. Teams need trend, filters by
rule/module/severity, and CSV/JSON summary export.

Desired behavior: `--history <dir>` appends a per-run summary (`runs.jsonl`) and renders a trend
chart; the report gains filters for rule, module and severity; `--summary-format CSV|JSON` writes a
machine-readable summary.

Data model: `RunSummary(timestamp, toolVersion, projectRoot, totalsBySeverity, totalsByRule,
totalsByModule, metrics, coverage, qualityGatePassed)`; `runs.jsonl` append-only, one line per run.

API surface: `HistoryStore.append(Path, RunSummary)`, `HistoryStore.load(Path, int lastN)`;
`HtmlReportExporter` takes an optional `List<RunSummary>`; `SummaryExporter` (CSV/JSON).

CLI / Maven flags: `--history <dir>`, `--history-limit <n>`, `--summary-format CSV|JSON`,
`--summary-file <path>`; Mojo `<history>`, `<historyLimit>`.

Test strategy: append/load round-trip; corrupt line skipped with a diagnostic; trend chart with 0,
1 and 5 runs; CSV escaping for messages with commas; golden HTML for filters.

Acceptance criteria: Three sequential runs produce a 3-point trend and stable totals; CSV opens in
Excel with correct quoting; no external chart library.

Risks / notes: Depends on E15 (streaming + determinism) — the timestamp must come from the injected
`Clock`. Do not add JavaScript chart libraries (air-gapped requirement).

---

## F3 implementation

- `rules/metadata/RuleDescriptor`, `RuleType`, `RuleRegistry` cover every `RuleId` with type, effort,
  tags, remediation, CWE and help URI; unknown ids (e.g. `java:S…`) get a Sonar-aware fallback.
- SARIF consumes the registry for `helpUri`, `fullDescription`, tags/CWE and default level.
- Sonar consumes the registry for issue type and effort (the two switch statements are gone).
- CLI `--list-rules` and `--explain <RULE>` (exit 2 for unknown ids).
- Tests: `RuleRegistryTest`, CLI listing/explain tests.

## F5 implementation

- New formats `GITHUB`, `GITLAB`, `AZURE` and exporters:
  - `GitHubAnnotationExporter` — workflow commands with correct escaping.
  - `GitLabCodeQualityExporter` — Code Climate JSON with baseline fingerprints.
  - `AzurePipelinesExporter` — `##vso[task.logissue]` lines.
- Wired through CLI and the shared Maven base (file names + exporters).
- Tests: `CiAnnotationExportersTest` (escaping, JSON fields, newline flattening).
- Remaining: GitHub's 10-annotation step limit batching and a self-scan upload workflow.
