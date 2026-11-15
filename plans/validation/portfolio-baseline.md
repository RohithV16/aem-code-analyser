# Baseline validation — `archetype-aem-project`

Date: 2026-11-15 · Validator: sub-agent (static analysis only) · Target repo untouched
(uncommitted user changes preserved; no checkout/reset/clean; all output written outside the target tree).

- Tool repo: `https://github.com/rohithv16/aem-code-analyser`
- Target repo: `/path/to/archetype-aem-project`
- Tool commit: `387dad2` (`fix(subservice): extract value for subservice key and avoid leak false positive on returned resolvers`)
- Tool version: `aem-analyser 1.0.0-SNAPSHOT`

## 1. Build

| Item | Value |
|---|---|
| Command | `mvn -q -DskipTests package` (run in tool repo) |
| Result | **SUCCESS** (exit 0); only Maven/Guice `sun.misc.Unsafe` warnings |
| Artifact | `aem-analyser-cli/target/aem-analyser-cli.jar` — 35,521,235 bytes, `sha256:6773523dd7bc2172…` |
| Built at | 2026-11-15 16:24 IST |
| Runtime used for scan | OpenJDK 21.0.8 (Homebrew), Maven 3.9.11 |
| Scan duration | ~6.0 s (second run; ~7.7 s cold) |

Note: the fat-jar manifest reports `Build-Jdk-Spec: 24` from an earlier build; the validation run used
JDK 21. The target repo pins `.cloudmanager/java-version` = **11**, which is unrelated to the analyser
runtime but worth knowing when reading bytecode-dependent rules.

## 2. Target repo inventory

### Modules (10 child POMs + root aggregator = "Modules: 11" in the report)

| Module | Packaging | Classification |
|---|---|---|
| `pom.xml` (root) | pom | reactor aggregator |
| `all` | content-package | container package |
| `core` | jar (+ `bnd.bnd`) | **BUNDLE_MODULE** (OSGi bundle) |
| `dispatcher` | pom (+ assembly) | dispatcher config module |
| `it.tests` | jar | integration tests (Java, not an OSGi bundle) |
| `ui.apps` | content-package | content package (HTL, dialogs, clientlibs) |
| `ui.apps.structure` | content-package | empty structure package (filter only) |
| `ui.config` | content-package | **OSGi configs** |
| `ui.content` | content-package | content package |
| `ui.frontend` | pom | webpack build module |
| `ui.tests` | pom | UI test module (no Java main sources) |

### Java

- **47** `.java` files under `core/src/main/java` (4 of them `package-info.java`).
- Report counts **51 classes / 2,900 LOC**, i.e. it also counts `it.tests` sources. Slight modelling
  mismatch: `it.tests` is a jar, not a bundle, but its sources are counted in "Java Source Files".
- Packages: `caconfig`, `constants`, `filters`, `listeners`, `models` (+ `models.elastic.data`),
  `schedulers`, `servlets`, `services.elastic` (+ `impl`), `utils`.

### Noteworthy AEM patterns

| Pattern | Instances |
|---|---|
| Servlets | 1 — `SimpleServlet` (`@SlingServletResourceTypes`, resourceType `rohithaem-portfolio/components/page`, GET, `txt`) — correctly *not* path-bound |
| Schedulers | 1 whiteboard scheduler (`ScheduledPollingScheduler`, `Scheduler.EXPR` + `schedule`), 1 demo `Runnable` (`SimpleScheduledTask`, never registered with `Scheduler`) |
| Event handlers | 2 — `SimpleResourceListener`, `DocumentReplicationEventHandler` (replication topic + event filter, PDF only) |
| Filters | 1 — `LoggingFilter` (`@Component(service = Filter.class)` + `@ServiceRanking`) |
| Sling Models | 1 — `HelloWorldModel` (used by `helloworld.html`) |
| OSGi services | ~10 impls, incl. factory-config `SiteIndexingProfileImpl`, validator chain, ES connection/config/orchestrator, cursor state |
| CAConfig | `SiteTagConfig` + `CAConfigHelper` (report shows **CloudConfigs: 0** — see gaps) |
| HTL | 9 `.html` files under `ui.apps` (2 `page`, 3 `xfpage`, `helloworld`, `search`, `es-search`, `elastic-metatags`) |
| Components | 43 `.content.xml` under `components/`, 4 clientlibs, **1** `cq_dialog` (rest inherit core components) |
| Dispatcher | 6 vhosts, 1 farm, filters/cache/rewrites/variables/clientheaders, `.cloudmanager/java-version = 11` |

### OSGi configuration (source only; `ui.config/target` ignored by the tool)

- **22** files, **100 % `.cfg.json`** (no `.config`, `.properties`, `.xml`).
- Runmode folders: `config`, `config.author`, `config.author.dev`, `config.author.prod`,
  `config.author.stage`, `config.dev`, `config.local`, `config.prod`, `config.publish`, `config.stage`
  (**10** folders).
- Composition: 14 project PIDs, **8 platform PIDs** (CORS `~`, MobileEmulator `~`, ToC filter `~`,
  LogManager factory `~`, RepositoryInitializer `~`, JcrResourceResolverFactoryImpl), 1 factory config
  (`SiteIndexingProfileImpl~en`), 3 `config.author.*` overrides of `ScheduledPollingServiceImpl`.
- Not in source but present in stale `ui.config/target/classes`: `ServiceUserMapperImpl.amended~rohith`
  and a repoinit variant with `create service user service-user-test` — see missed findings.

## 3. CLI invocation

```bash
java -jar aem-analyser-cli/target/aem-analyser-cli.jar \
  --project-root /path/to/archetype-aem-project \
  --format CONSOLE,SARIF,SONAR,HTML \
  --output /private/var/folders/2d/yvkjbkqx4hj__hy29qgrqtxw0000gp/T/aem-analyser-baseline \
  --no-color
```

- **Exit code: 1** (`BUILD FAILURE: 7 critical cross-boundary errors detected.`)
- Output (outside the target repo): `console.log`, `aem-analyser.sarif` (20 results),
  `sonar-aem-analyser-report.json`, `aem-analyser-report.html`.
- Console log is reproducible: two consecutive runs produced the identical 20-violation set.

## 4. Findings

**Total: 20 (7 ERROR, 13 WARNING, 0 INFO).**

| Rule | Severity | Count | Nature |
|---|---|---|---|
| AEM-PERF-002 | ERROR | 1 | Unclosed JCR session (adaptTo) |
| AEM-PERF-001 | ERROR | 1 | Unclosed ResourceResolver |
| AEM-SEC-001 | ERROR | 4 | `@ context='unsafe'` in HTL (2 unique lines) |
| AEM-CLOUD-001 | ERROR | 1 | Plaintext password in `config.local` |
| AEM-DEAD-002A | WARNING | 1 | Ghost config property `disabled` |
| AEM-DUP-010 | WARNING | 1 | Duplicate code block (intra-class) |
| java:S1166 | WARNING | 3 | Exception caught without logging/rethrowing |
| java:S1192 | WARNING | 3 | Duplicated string literal |
| java:S1186 | WARNING | 2 | Empty `Filter.init` / `Filter.destroy` |
| java:S100 | WARNING | 1 | Bad method name |
| java:S2208 | WARNING | 1 | Wildcard import (`it.tests`) |
| java:S1155 | WARNING | 1 | `size() == 0` instead of `isEmpty()` (`it.tests`) |

Top files: `xfpage/customfooterlibs.html` (2), `xfpage/customheaderlibs.html` (2),
`CursorStateServiceImpl` (3), `LoggingFilter` (2), `ScheduledPollingServiceImpl` (2),
`PublishPageValidationIT` (2), then one each for the remaining files/configs.

### Classification summary

| Class | Count |
|---|---|
| TRUE positives | 9 (3 AEM + 6 Sonar) |
| FALSE positives | **8** (4 of the 7 ERRORs) |
| Suspicious / policy calls | 3 |

The non-zero exit code is driven entirely by false positives plus one local-only credential file —
see §5 and §6.

## 5. FALSE POSITIVES (curated)

| # | File:line | Rule | Why it is wrong |
|---|---|---|---|
| 1 | `core/.../impl/CursorStateServiceImpl.java:43` | AEM-PERF-002 | `session` is `resolver.adaptTo(Session.class)` at line 43 **inside** `try (ResourceResolver resolver = …)` opened at line 42. Closing the resolver releases the adapted session. The rule tracks `adaptTo(Session.class)` as an owned session and ignores the enclosing try-with-resources resolver. |
| 2 | `core/.../impl/SiteIndexingProfileRegistry.java:80` | AEM-PERF-001 | `ResourceResolver resolver = asset.getResourceResolver();` is a **borrowed** resolver supplied by the caller. The method must not close it; closing it would break the caller's request-scoped resolver. Rule must only flag resolvers obtained from `ResourceResolverFactory`. |
| 3 | `ui.apps/.../components/xfpage/customfooterlibs.html:25` | AEM-SEC-001 | Duplicate emission: same file/line/column reported twice. Root cause is the **root aggregator POM being scanned as a module**, so `ui.apps` is walked twice (`HtlUsages: 20` = 10 real `data-sly-use` occurrences × 2; 4 SEC findings = 2 lines × 2 scans). |
| 4 | `ui.apps/.../components/xfpage/customheaderlibs.html:29` | AEM-SEC-001 | Same duplicate-emission bug as #3. |
| 5 | `core/.../impl/SiteIndexingProfileImpl.java:45` | java:S100 | `webconsole_configurationFactory_nameHint()` is a **reserved OSGi Metatype method name**; renaming it silently breaks the Web Console factory name hint. AEM-aware Sonar profile should exclude it. |
| 6 | `core/filters/LoggingFilter.java:62` | java:S1186 | Empty `init(FilterConfig)` is the correct no-op implementation of the Servlet `Filter` lifecycle contract. |
| 7 | `core/filters/LoggingFilter.java:66` | java:S1186 | Empty `destroy()` — same framework-contract no-op. |
| 8 | `core/.../impl/CursorStateServiceImpl.java:53` | java:S1192 | `parent.addNode("cursor", "nt:unstructured")` is a **JCR node name**. `PROPERTY_CURSOR` is the *property* name that happens to share the string. The suggested fix conflates two concepts and the message ("use already-defined constant") is misleading. |

Confirmed false positives: **8**. Four of them (`PERF-001`, `PERF-002`, two `SEC-001`) are ERRORs, so
the gate would fail on a clean project.

### Suspicious (not counted as FP, but non-actionable)

| File:line | Rule | Note |
|---|---|---|
| `xfpage/customfooterlibs.html:25` / `customheaderlibs.html:29` (the non-duplicate copies) | AEM-SEC-001 | Adobe Core Components boilerplate: element name comes from the `Page` model's `htmlPageItems`, attributes go through `data-sly-attribute`. Real `context='unsafe'`, but the safe fix is to treat Core Components boilerplate as an allowlist/suppression candidate rather than an ERROR that blocks every archetype-derived project. |
| `core/.../impl/SearchConnectionServiceImpl.java:128` | java:S1192 | `payload.put("status", "new")` reuses the *value* of `STATUS_KEY`, but the constant is used for response parsing. Low value, borderline semantics. |

## 6. TRUE POSITIVES worth keeping

| File | Rule | Why it matters |
|---|---|---|
| `ui.config/.../config.local/…SearchConnectionServiceImpl.cfg.json:4` | AEM-CLOUD-001 | Real committed credential (`"esPassword": "admin123"`). Caveat: `config.local` is **not** a Cloud Manager-deployed runmode, and the same default is hardcoded in the OCD, so ERROR-level gating is too aggressive — see follow-ups. |
| `ui.config/.../config.local/…ScheduledPollingServiceImpl.cfg.json:9` | AEM-DEAD-002A | Genuine: `"disabled": true` has **no** `disabled()` in `ScheduledPollingServiceImpl.Config`, and the component is `configurationPolicy = REQUIRE`. The flag silently does nothing; the operator believes polling is off while it is active. Highest-value finding in the run. |
| `core/.../impl/ScheduledPollingServiceImpl.java:214` | AEM-DUP-010 | Verified: lines 112–125 and 214–227 build the same `SSLConnectionSocketFactory` + `RequestConfig` + `CloseableHttpClient` triple. Extract a helper (matches the rule's own remediation). |
| `core/.../impl/DocumentReplicationEventHandler.java:51` | java:S1166 | Catch logs the topic but drops the exception object — debugging loss on unknown replication types. |
| `core/.../impl/ScheduledPollingServiceImpl.java:249` | java:S1166 | `getBaseUrl` swallows `URISyntaxException` and returns the raw URL, silently masking malformed journal URLs (also relevant to the SSRF gap below). |
| `it.tests/.../PublishPageValidationIT.java:87` | java:S1166 | Swallowed exception in an integration test assertion path. |
| `core/.../impl/CursorStateServiceImpl.java:49` | java:S1192 | Literal `"journalapi"` repeated 3× — a genuine constant candidate. |
| `it.tests/.../GetPageIT.java:24` | java:S2208 | Wildcard import — valid style finding. |
| `it.tests/.../PublishPageValidationIT.java:114` | java:S1155 | `size() == 0` — valid style finding. |

### Positive controls (no false positives observed — keep this behaviour)

- **Platform PIDs correctly exempt:** `CORSPolicyImpl~`, `MobileEmulatorProvider~` (including its
  `README` key), `TableOfContentsFilter~`, `LogManager.factory.config~`, `RepositoryInitializer~`,
  `JcrResourceResolverFactoryImpl`.
- **`config.author` / `config.publish` overrides not flagged as duplicates:** AEM-DUP-002 = 0, correct
  (runmode precedence, not duplication).
- **No orphaned-config findings** (AEM-DEAD-001 = 0): every project PID has a matching component.
- **No JSP findings** (AEM-CLOUD-002 = 0), no classic `dialog.xml` (AEM-COMP-001 = 0) — both correct.
- **`it.tests` and `ui.config/target` are not scanned for configs** — `OsgiConfigs: 22` equals the
  source count exactly (target copies correctly ignored).

## 7. Findings the tool MISSED

| # | Expected rule | Evidence | Why missed |
|---|---|---|---|
| 1 | **AEM-CLOUD-003** (UnmappedSubserviceUser, ERROR) | `CursorStateServiceImpl.java:29` and `:42` call `getServiceResourceResolver(null)`. Source tree has **no** `ServiceUserMapperImpl.amended` and **no** repoinit `create service user`; the only mapping lives in stale `ui.config/target/classes`. At runtime the login has no mapping → null resolver / failed service. | `AemCloud003Rule` only evaluates calls whose subservice is non-null and non-blank (`if (subservice != null && !subservice.isBlank() && !mappedSubservices.contains(subservice))`). Calls with `null`/empty subservice are skipped instead of being checked against the bundle's default mapping. |
| 2 | **AEM-QUAL-003** (SlingSchedulerUsage, WARNING) | `ScheduledPollingScheduler` imports `org.apache.sling.commons.scheduler.Scheduler` and calls `scheduler.EXPR(...)` / `scheduler.schedule(...)`; AMSCORE-554 territory. | Rule only inspects component property names (`scheduler.expression=`, `scheduler.period=`) or `ScheduleOptions` *interface implementation*. API-based whiteboard scheduling with an OCD key `scheduler_expression` is invisible to it. |
| 3 | Scheduler entry point that cannot do work | `ScheduledPollingScheduler.run()` only logs `"Journal polling scheduler triggered"` — it never calls `ScheduledPollingService`. `SimpleScheduledTask` is a `Runnable` OSGi service that is never registered with the `Scheduler`. | No rule models "scheduled/entry-point component with no effective work" or "Runnable service never scheduled". Natural extension of AEM-DEAD-003C / DEAD-003A rather than a new rule family. |
| 4 | **AEM-SEC-002** SSRF / token exfiltration (planned) | Public `pollJournalEvents(String journalUrl, …)` accepts an arbitrary URL, only checks `callUrl.startsWith("https://")`, then sends `clientId`/`Authorization` to that host; `callUrl` can also come from the persisted cursor. | Rule not implemented (tracked as `F9`). This project is a textbook trigger — good acceptance fixture for the future rule. |
| 5 | CAConfig modelling | `SiteTagConfig` + `CAConfigHelper` exist, yet the report shows `CloudConfigs: 0`. | caconfig annotations are not discovered/mapped; no rule consumes them. Covered conceptually by `F7` (cross-boundary expansion), but the zero count is silently misleading. |
| 6 | Diagnostics for skipped/ambiguous inputs | `ui.apps/target/classes` contains a `chatbot` component and the service-user mapping that no longer exist in `src`. | Analyser silently ignores `target/`, which is correct for *source* analysis, but there is no diagnostic telling the user that build output diverges from source (`E11` diagnostics gap). |
| 7 | Severity policy for local-only runmodes | `config.local` (and arguably `config.dev`) are not deployed by Cloud Manager, yet AEM-CLOUD-001 there is ERROR and gates the build. | No runmode-aware severity/scope policy (`F2` severity overrides / `F8` runmode-folder sanity). |
| 8 | Duplicate emissions are not collapsed | Whole `ui.apps` scanned twice because the root aggregator is treated as a module. | No path-normalised dedupe of violations before reporting (`O4` determinism, `E8` discovery semantics). |

Not missed (checked, correctly absent): unbounded JCR/Oak queries (no JCR-SQL2/XPath in the codebase,
so AEM-PERF-003 legitimately 0), administrative login (AEM-QUAL-005, 0), path-bound servlets
(AEM-QUAL-001, 0), duplicate servlet endpoints (AEM-DUP-001, 0), broken HTL model references
(AEM-DEAD-004, 0), unused Sling Models (only `HelloWorldModel`, bound to `helloworld.html`).

## 8. Follow-ups for the backlog

| Priority | Item | Plan reference |
|---|---|---|
| **P0** | Stop treating the root aggregator POM as a scannable module (or dedupe violations by normalised path+line+column+rule). Fixes 2 duplicate SEC-001 findings and any future double-counting of HTL components. | `E8` (discovery/Maven semantics), `O4` (deterministic ordering); propose **C10** |
| **P0** | Sink-aware resource lifecycle: never flag a session adapted from a resolver that is itself try-with-resources-managed; never flag `asset.getResourceResolver()` / passed-in resolvers. Add both fixtures to the TP/FP suite. | propose **C11**, verify with `O7` golden fixtures |
| **P0** | AEM-CLOUD-003: evaluate `getServiceResourceResolver(null)` and empty subservices against the bundle's default `user.mapping`; treat "no mapping and no service user anywhere" as ERROR. | `F7` (repoinit/service-user consistency), `E2`/`E4` |
| P1 | AEM-QUAL-003: detect `Scheduler.EXPR/PERIOD/AT/schedule` API usage, not just `scheduler.*` properties; include `scheduler_expression` OCD-style keys. | propose **E16** |
| P1 | New entry-point rule: `Runnable` service never scheduled, and scheduled `run()` with no service call / no side effects (dead scheduler). | `F9` (new rules), extend `AEM-DEAD-003C` |
| P1 | AEM-SEC-002 SSRF: flag user/config-sourced URLs passed to HTTP clients with credentials; this repo is the acceptance fixture. | `F9` |
| P1 | Runmode-aware severity: config-only, non-deployed runmodes (`local`, optionally `dev`) → WARNING + distinct scope; keep prod/publish/author at ERROR. | `F2`, `F8` |
| P1 | AEM-aware Sonar suppressions: `webconsole_configurationFactory_nameHint` (S100) and Servlet `Filter.init/destroy` (S1186) must not be reported. | propose **E17** |
| P2 | Model wcm.io CAConfig (`@CAConfig`, `ConfigurationMetadata`) so `CloudConfigs` is not silently 0; surface the count as a diagnostic when annotations are present but unmodelled. | `F7`, `E11` |
| P2 | Baseline/suppression so this repo can adopt the gate today: suppress the Core Components `xfpage` boilerplate instead of editing Adobe-derived files. | `F1` |
| P2 | Fix S1192 heuristic: do not suggest a constant when the literal is a JCR node name/type or a different semantic role than the constant. | propose **E18** |

## 9. Verdict

The engine builds cleanly and is fast (≈6 s on an 11-module project). Discovery of modules, OSGi
configs and platform-PID exemptions is solid — zero false positives on the framework PIDs and zero
spurious duplicate-config findings. The gate is **not adoptable yet**: all but one ERROR is a false
positive or a local-only dev credential, and a genuine deployment blocker (AEM-CLOUD-003, no service
user mapping for `getServiceResourceResolver(null)`) is missed. Fix the two P0 lifecycle/discovery
items, then re-run this baseline and expect ERROR count to drop to 1 (CLOUD-001, downgraded) while
CLOUD-003 becomes the legitimate gating finding.

---

## 10. Wave 1 re-validation (2026-11-15, after C1–C15)

Exact command (output outside the target repo):

```bash
java -jar aem-analyser-cli/target/aem-analyser-cli.jar \
  --project-root /path/to/archetype-aem-project \
  --format CONSOLE,SARIF --output /private/var/folders/.../aem-analyser-wave1c --no-color
```

Result: **13 findings (5 ERROR, 8 WARNING), 0 duplicates, exit 1** — down from 20 findings / 7 ERROR
with 2 duplicate emissions.

| Rule | Severity | Before | After | Change |
|---|---|---|---|---|
| AEM-PERF-001 | ERROR | 1 | 0 | FP fixed (borrowed resolver) |
| AEM-PERF-002 | ERROR | 1 | 0 | FP fixed (TWR-owned resolver) |
| AEM-SEC-001 | ERROR | 4 | 2 | duplicate emissions collapsed |
| AEM-CLOUD-001 | ERROR | 1 | 1 | genuine local credential |
| AEM-CLOUD-003 | ERROR | 0 | 2 | **missed defect now detected** (`getServiceResourceResolver(null)`) |
| AEM-DEAD-002A | WARNING | 1 | 1 | genuine ghost property |
| AEM-DUP-010 | WARNING | 1 | 1 | genuine clone |
| java:S1186 | WARNING | 2 | 0 | AEM-aware suppression (Filter lifecycle) |
| java:S100 | WARNING | 1 | 0 | AEM-aware suppression (Metatype reserved name) |
| java:S1192 | WARNING | 3 | 1 | JCR node-literal FP suppressed; genuine constant remains |
| java:S1166 / S2208 / S1155 | WARNING | 5 | 5 | genuine |

Structural checks:
- `HtlUsages: 10` (was 20) → reactor root is no longer scanned as a content module.
- `OsgiConfigs: 22`, `Modules: 11`, `CloudConfigs: 0` unchanged.
- Zero duplicate `(rule, file, line, column)` tuples in SARIF.

Controlled edit test: a temporary orphan config
`ui.config/.../osgiconfig/config.prod/com.example.analyser.probe.OrphanConfig.cfg.json` was added,
the analyser reported exactly one `AEM-DEAD-001` for that PID, and the file was removed afterwards.
`git status` on the target repo is unchanged.

Remaining triage:
- `AEM-SEC-001` × 2: Core Components `xfpage` boilerplate using `context='unsafe'`. Real unsafe
  context, but an Adobe-derived file. Adopt F1 (baseline/suppression) or an allowlist rather than
  editing vendor code.
- `AEM-CLOUD-001`: `config.local` is not a Cloud Manager-deployed runmode; severity/scope policy is
  tracked under F2/F8.
- `AEM-CLOUD-003` × 2: legitimate deployment blocker — this is the new gating finding.

### Wave 2 re-check (C8, E4, E7, E8, E10)

- 13 findings, 5 ERROR, 0 duplicates — unchanged from wave 1 (no regressions).
- SARIF `tool.driver.version` is now `1.0.0-SNAPSHOT` (filtered from the build) and
  `informationUri` points at `rohithv16/aem-code-analyser`.
- E4 (`service` attribute semantics) briefly surfaced one `AEM-DEAD-003A` on
  `ScheduledPollingScheduler`; the root cause was that implemented interfaces were never stored on the
  component node. Populating them restored the whiteboard entry-point exemption and the finding
  disappeared. This is now covered by `OsgiComponentVisitorTest`.

### Wave 3/4 re-check (E11 diagnostics, E6 activation semantics, E4 references)

- 13 findings, 5 ERROR, 0 duplicates — unchanged; no regressions.
- Analysis diagnostics: 0 on this project (all Java/config files parse cleanly). `--verbose` is a
  no-op here, which is the expected healthy state.
- `AEM-DEAD-003C` and `AEM-DEAD-003A` counts unchanged (0), confirming the new factory/disabled/
  BundleContext and bind-method exemptions did not hide an existing finding.
- `mvn clean verify`: 142 tests, 0 failures.

### Wave 5 re-check (F1 baseline + inline suppression)

- `--update-baseline --baseline <tmp>/baseline.json` accepted 13 findings and exited **0**.
- Re-running with `--baseline <tmp>/baseline.json` suppressed 13 findings, wrote 0 SARIF results and
  exited **0**.
- Inline-suppression regression tests cover next-line, file-level and wildcard directives for Java,
  HTL and XML comment syntax.
- Build: `mvn clean verify`, 148 tests, 0 failures.

### Wave 6 re-check (E9/E12/E13/E14/E15/E8/E3/O3/O4/O8)

- 13 findings, 5 ERROR, 0 duplicates — unchanged.
- Build: `mvn clean verify`, **159 tests**, 0 failures.
- Added: robust git diff (renames/untracked/spaces/merge-base), CLI numeric validation and new
  options, Maven parameter parity guard + `<format>` alias, dependency-JAR flags, deterministic HTML
  (fixed clock), capped HTML rows, control-character escaping, plain-jar/`-parent`/conf-only module
  classification, 10 MiB file guards with diagnostics, deterministic scan-statistic ordering,
  `@ComponentPropertyType` parsing, and `CONTRIBUTING.md`/`SECURITY.md`/`CHANGELOG.md`.

### Wave 7 re-check (F3 rule registry, F5 CI formats)

- 13 findings, 5 ERROR, 0 duplicates — unchanged.
- `--format GITHUB,GITLAB,AZURE` produced 13 GitHub workflow-command lines, 13 GitLab Code Quality
  issues and 13 Azure `##vso` lines.
- Fixed during validation: the GitLab exporter closed the caller's writer (Jackson
  `AUTO_CLOSE_TARGET`); disabling it and a regression test keep the CLI/Maven writer alive.
- Build: `mvn clean verify`, **168 tests**, 0 failures.
- `--list-rules` and `--explain AEM-DEAD-002A` render from the single registry.
