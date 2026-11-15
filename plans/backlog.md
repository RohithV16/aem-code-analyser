# Backlog — AEM Code Analyser

Prioritized backlog mapped to concrete classes. Verified items were confirmed by reading the
current code, not only the review handoffs under `.agents/teamwork/`.

Legend: **P0** release blocker/correctness · **P1** edge-case hardening · **P2** feature/optimization.

---

## P0 — Correctness bugs / release blockers

### C1 — `ignoredConfigPaths` is declared but never consumed
- `core/api/AnalysisContext.java` — field exists and is copied defensively.
- `core/api/DefaultAemCodeAnalyser.parseOsgiConfigs()` ignores it.
- `core/discovery/OsgiConfigScanner.scanModule()` has no ignore predicate.
- CLI has no `--ignore-config`; `CheckMojo`/`ReportMojo` expose nothing.
- **Fix:** path-pattern matcher (reactor-relative glob), filter in scanner + parser loop, expose CLI/Maven.
- **Acceptance:** ignored file produces zero violations; unit test covers glob and absolute/relative forms.

### C2 — Runmode index key mismatch
- `core/graph/ReferenceGraphIndex.registerConfig()` stores under `"author+dev"` (joined, sorted).
- `getConfigsForRunmode("author")` looks up `"author"` → always empty.
- **Fix:** index each runmode individually (and optionally keep a combined key); add unit test.

### C3 — PID grammar rejects hyphens
- `core/parser/config/ConfigFilePidResolver.PID_GRAMMAR` base PID is `[a-zA-Z0-9_]+`.
- Hyphenated PIDs such as `com.example.my-service.cfg.json` are rejected and silently skipped by
  `OsgiConfigScanner`.
- **Fix:** allow `-` in PID segments; keep factory delimiters unambiguous; tests for `~` and legacy `-`.

### C4 — `AEM-DEAD-002A` only validates first component and misuses normalizer
- `core/rules/impl/AemDead002ARule.java` uses `components.get(0)`.
- It normalizes the **config key** with `MetatypeNameNormalizer.normalizeMethodName(...)`, which is
  designed for Java method names, not OSGi property keys.
- **Fix:** aggregate all components for the PID; resolve properties against every OCD; honour
  `@AttributeDefinition(id=...)`; keep OSGi key case-sensitivity explicit.

### C5 — `AEM-DUP-010` has two producers; one is dead code
- `core/rules/impl/AemDup010Rule.java` implements `AEM-DUP-010` but is **not registered** in
  `DefaultAemCodeAnalyser.rules`.
- `core/discovery/PmdCpdDuplicationEngine` emits `AEM-DUP-010` and is registered.
- **Fix:** delete the dead rule + its test, or make the rule the single producer and derive metrics
  from it. Deleting is the smaller, safer change; document it.
- **Acceptance:** exactly one class produces `AEM-DUP-010`; `DuplicateRulesTest` updated.

### C6 — `--fix` produces a stale report and exit code
- `cli/AnalyserCli.run()` analyses → auto-fixes → exports/exit code from the **pre-fix** report.
- **Fix:** re-analyse after a non-dry-run fix (or recompute violations from post-fix state) before
  export/exit; keep dry-run semantics unchanged.
- **Acceptance:** project with one auto-fixable issue exits 0 after `--fix`; SARIF has no fixed issue.

### C7 — CLI/Maven docs advertise parameters that do not exist
- README documents CLI `--output-file`; actual option is `--output <dir>`.
- README documents Maven `<format>` and `<outputFile>` on `check`; `CheckMojo` has neither.
- `ReportMojo`/CLI call `ReportFormat.valueOf(...)` unguarded → raw `IllegalArgumentException`.
- **Fix:** implement `formats`/`outputFile` on both Mojos (shared base class), validate formats with a
  clean error (exit 2 / `MojoFailureException`), and correct the README.
- **Acceptance:** documented config works; invalid format yields actionable message and exit code 2.

### C8 — `ambiguousHyphen` is computed but never consumed
- `ConfigFilePidResolver.ResolvedPid.ambiguousHyphen()` is propagated to `OsgiConfigDefinition` but
  no rule or graph step uses it.
- Legacy `.config` names like `my.pid-sub.name` are genuinely ambiguous.
- **Fix:** disambiguate at graph time against known factory PIDs, or remove the field and document
  the limitation. Prefer disambiguation with a logged diagnostic.
- **Acceptance:** legacy dotted/hyphenated factory fixture resolves to the right PID.

### C9 — Missing `LICENSE`
- README shows Apache-2.0 badge and links `LICENSE`; the file does not exist.
- **Fix:** add `LICENSE` (Apache-2.0) and reference it in `pom.xml` `<licenses>`/`<inceptionYear>`.

---

## P1 — Edge-case hardening

### OSGi rules
- **E1** `AemDead001Rule`, `PlatformPidCatalog`, `ReferenceGraphIndex` — explicit `configurationPid`,
  `$` expansion, `configurationPolicy=IGNORE`, factory configs (`~`, legacy `-`), OCD-named configs,
  platform/external PIDs, component `name` vs class FQN.
- **E2** `AemDead002ARule`, `MetatypeNameNormalizer`, `FrameworkPropertyWhitelist`,
  `CfgJsonConfigParser` — shared OCD, explicit `@AttributeDefinition(id=...)`, typed keys
  (`:Integer`, `:String[]`), env/secret placeholders, `:` metadata keys, arrays/objects, key case
  sensitivity, whitelist exact/prefix collisions.
- **E3** `AemDead002BRule`, `ComponentPropertyConsumptionVisitor` — constructor injection, field
  access, superclass access, `@ComponentPropertyType`, getters, `@Activate` methods, inherited OCD
  methods.
- **E4** `AemDead003ARule`, `SystemEntryPointCatalog`, `ExportPackageCollector` — `service = {}` vs
  `Object.class`, `@Reference` cardinality/policy, `servicefactory`, self-references, exported
  interface patterns/directives, whiteboard SPIs (DataSource, ResourceProvider, request listeners,
  CAConfig, webconsole plugins).
- **E5** `AemDead003BRule`, `HtlScanner`, `JcrComponentScanner` — dynamic HTL expressions,
  `data-sly-resource`, `resourceType` overrides, `@Model(adapters=...)`, `@Via`,
  `ModelFactory.createModel`, JSP/Java FQN use, `@Exporter`.
- **E6** `AemDead003CRule`, `OsgiComponentVisitor` — DS 1.4 `immediate` defaults, `ServiceTracker` /
  `BundleContext` usage, `factory` components, `configurationPolicy=REQUIRE`, `enabled=false`.

### Config parsing (E7)
`CfgJsonConfigParser`, `FelixConfigParser`, `PropertiesConfigParser`, `XmlOsgiConfigParser`,
`ConfigKeyParser` — duplicate JSON keys, BOM/encoding, nested arrays/objects, escapes, `.config`
typed syntax (`I"…"`, `L"…"`, arrays), `.content.xml` namespace/prefix variants, runmode
precedence/merge.

### Discovery (E8)
`ProjectDiscoveryEngine`, `ModuleClassifier` — Maven profile semantics (explicit profile disables
`activeByDefault` in the same POM), `pom`-without-modules fallback, `jar` always classified as
`BUNDLE_MODULE`, enterprise layouts (nested aggregators, `all` containers, `ui.apps/config`,
`ui.acl`, `it.tests` jars, `pipelines` POMs).

### Reporting / CLI / Maven
- **E9** `GitDiffResolver` — porcelain renames, spaces/unicode/quotes, untracked dirs, `--since`
  two-dot vs `since...HEAD`, subdirectory invocation.
- **E10** `SarifReportExporter`, `SonarReportExporter` — schema validation, `endColumn > startColumn`,
  `helpUri`/`fullDescription`, correct `informationUri`, version from build (not hardcoded `1.0.0`),
  rule-derived `type`/`effortMinutes`, newer Sonar `cleanCodeAttribute`/`impacts`.
- **E11** `DefaultAemCodeAnalyser` — replace silent `catch`-and-skip with `AnalysisDiagnostics`
  (file + reason) surfaced by `--verbose` and in the report.
- **E12** `AnalyserCli` — invalid `--format`, `--fail-on` handling, `--sonar`/`--no-sonar`,
  `--fix` + `--changed-only`, `System.exit` inside `run()` hampers tests.
- **E13** `CheckMojo`, `ReportMojo` — consistent parameters (`profiles`, `threads`,
  `ignoredServices`, `ignoredConfigPaths`, `enableSonar`, `sonarRules`, `formats`, `outputFile`);
  Maven Invoker IT.
- **E14** `AnalysisContext`, `indexDependencies`, CLI — `dependencyJarRoots` javadoc says it defaults
  to `~/.m2/repository` but nothing sets it; implement default or fix docs; add `--dependency-jars`.
- **E15** `HtmlReportExporter` — escape all violation text, no external assets, bounded size.

---

## P2 — Features

- **F1** Suppression + baseline: `.aem-analyser-baseline.json`, fingerprints,
  `// aem-analyser-disable-next-line AEM-DEAD-001`, `--baseline`, `--update-baseline`.
- **F2** Project config `.aem-analyser.yml`: rule enable/disable, severity overrides, thresholds,
  allowlists, ignored paths.
- **F3** Central rule metadata registry (help URI, docs, tags, remediation, CWE) feeding
  SARIF/Sonar/HTML; `--list-rules`, `--explain <RULE>`.
- **F4** Incremental cache keyed by content hash + tool/rule version; make `--changed-only` a true
  fast path.
- **F5** CI annotations: GitHub Code Scanning SARIF upload, GitLab Code Quality, Azure DevOps,
  `--format GITHUB`.
- **F6** More safe fixers with preview diff, atomic writes, encoding preservation, idempotency;
  avoid regex edits inside strings/comments.
- **F7** Cross-boundary expansion: HTL ↔ model/dialog property binding, clientlib dependency/orphan
  detection, Granite dialog `@ValueMapValue`, repoinit/service-user consistency.
- **F8** AEMaaCS governance: CDN/WAF/rate-limit YAML, dispatcher rules, log forwarding, secret
  references, runmode-folder sanity.
- **F9** New rules: `AEM-SEC-002` SSRF/open redirect, `AEM-PERF-004` unbounded query,
  `AEM-QUAL-006` resolver/session misuse, `AEM-CLOUD-004` deprecated API usage.
- **F10** HTML trend/history dashboard, filters by rule/module/severity, CSV/JSON summary export.

---

## P2 — Optimization, quality, release engineering

- **O1** Parallelize config parsing, HTL/JCR scanning, Sonar per-file; filter by module classification.
- **O2** Cache `collectModules(model)`; avoid repeated `Files.walk`.
- **O3** Stream large `.content.xml` with StAX; max-file-size guard; explicit binary skips.
- **O4** Deterministic ordering across modules/files/rules/threads; Windows path normalization;
  stable JSON key order.
- **O5** Cancellation/timeouts, OOM-safe limits, structured diagnostics, `--max-file-size`,
  `--timeout`.
- **O6** `maven-enforcer-plugin` (Java 21 / Maven 3.9), Spotless/Checkstyle, release profile from
  `docs/MAVEN_CENTRAL_PUBLISHING_GUIDE.md`, CycloneDX SBOM, `japicmp`, reproducible builds.
- **O7** Test expansion: parameterized parser tests, golden TP/FP fixtures per rule, property-based
  PID/normalizer/placeholder tests, Maven Invoker IT, SARIF/Sonar schema validation.
- **O8** Docs sync (`README`, `PROJECT.md`, `docs/`), `CONTRIBUTING.md`, `SECURITY.md`,
  `CHANGELOG.md`, ADRs for rule semantics.
