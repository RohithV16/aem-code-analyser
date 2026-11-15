# Enhancement backlog — 19 items

Status legend: `Planned` → `In progress` → `Done`.

| # | Item | Effort | Status |
|---|---|---|---|
| 1 | Generate `docs/rules/<ID>.md` from `RuleRegistry` | S | Done |
| 2 | Build hygiene: enforcer, reproducible builds, quality profile | S | Done |
| 3 | SARIF/Sonar schema-conformance tests | S | Done |
| 4 | CI matrix (Linux/macOS/Windows, Java 21) | S | Done |
| 5 | Maven Invoker integration tests | M | Done |
| 6 | CLI `execute()` seam (no `System.exit` in analysis flow) | S | Done |
| 7 | Configurable `--max-file-size` and `--timeout` | M | Done |
| 8 | Per-bundle service-user mapping | M | Done |
| 9 | Sling Models/HTL depth (`@Via`, `@Exporter`, multiline HTL) | M | Done |
| 10 | Duplicate detector hardening (comments, offsets, block fingerprints) | M | Done |
| 11 | Runmode merge/precedence model | M | Done |
| 12 | Incremental parsing / true `--changed-only` fast path | M | Done |
| 13 | `.aem-analyser.yml` project configuration | M | Done |
| 14 | Baseline UX (suppressed counts, `--baseline-report`) | S | Done |
| 15 | F7 slice: HTL/model/dialog + clientlib/repoinit cross-checks | L | Done |
| 16 | F8 slice: AEMaaCS governance validators | L | Done |
| 17 | F9 rules: SSRF, unbounded query, resolver misuse, deprecated API | L | Done |
| 18 | F10: run history + HTML trend dashboard | M | Done |
| 19 | Release engineering (Central profile, SBOM, japicmp, 1.0.0) | M | Done |

## Design notes

1. **Docs generator** — `RuleDocsGenerator.writeAll(Path)` emits one Markdown page per
   `RuleDescriptor`; CLI `--generate-rule-docs <dir>`; generated `docs/rules/` committed.
2. **Hygiene** — `maven-enforcer-plugin` (Java `[21,22)`, Maven `[3.9,4)`), fixed
   `project.build.outputTimestamp`, `quality` profile with Spotless/Checkstyle.
3. **Schema tests** — structural conformance tests for SARIF 2.1.0 and Sonar generic issues,
   including required fields and coordinate invariants.
4. **CI** — matrix `ubuntu/macos/windows` on Java 21, plus a `run-its` job.
5. **Invoker** — `run-its` profile with a tiny fixture reactor invoking `aem-analyser:report`.
6. **CLI seam** — `run()` delegates to `int execute()`; all error paths return codes.
7. **Limits** — `AnalysisContext.maxFileSizeBytes`/`timeoutMillis`, CLI/Maven flags, deadline-aware
   `Future.get`, `FILE_TOO_LARGE`/`ANALYSIS_TIMEOUT` diagnostics.
8. **Per-bundle mapping** — parse `Bundle-SymbolicName` from `bnd.bnd`/pom, map caller file → bundle,
   key `user.mapping` by `(bundle, subservice)`, fall back to project-wide only when unknown.
9. **Models/HTL** — `@Via`/`@Exporter`, multiline and dynamic `data-sly-use`, `data-sly-resource`.
10. **Duplicates** — comment-aware line counts, first-code-line normalisation, block fingerprint,
    both-path messages (already present).
11. **Runmode merge** — `RunmodeResolver` computes effective config per PID with precedence
    `config < config.author < config.author.dev`.
12. **Incremental** — `--changed-only` filters the file set before parsing; cache parsed phase-A
    products keyed by content hash + tool version.
13. **YAML config** — `AnalyserConfig` (rule enable/severity/allowlists), CLI `--config`, Maven
    `<configFile>`.
14. **Baseline UX** — suppressed counts in console/SARIF, `--baseline-report` keeps suppressed
    findings at INFO.
15. **F7** — clientlib orphan/cycle detection and repoinit/service-user consistency rule.
16. **F8** — runmode-folder sanity, dispatcher allow-all filters, Cloud Manager secret references.
17. **F9** — `AEM-SEC-002` SSRF, `AEM-PERF-004` unbounded query, `AEM-QUAL-006` resolver misuse,
    `AEM-CLOUD-004` deprecated API usage.
18. **F10** — append `runs.jsonl`, render trend sparkline and run table in HTML.
19. **Release** — `release-central` profile, source/javadoc/GPG, CycloneDX SBOM, japicmp, docs.

## Implementation notes (2026-11-15)

- **1** `RuleDocsGenerator` + CLI `--generate-rule-docs`; 30 pages generated under `docs/rules/`.
- **2** Enforcer (Java 21, Maven 3.9), reproducible `outputTimestamp`, `quality` profile
  (Checkstyle report + Spotless configured), release/API profiles.
- **3** `SarifSchemaConformanceTest` and `SonarSchemaConformanceTest` (required fields/coordinates).
- **4** CI matrix Linux/macOS/Windows + Invoker + quality jobs.
- **5** `run-its` profile and `aem-analyser-maven-plugin/src/it/basic`; verified locally.
- **6** `AnalyserCli.execute()` returns codes; `run()` only exits.
- **7** `--max-file-size`, `--timeout`, Maven `<maxFileSizeBytes>`/`<timeoutSeconds>`, partial-report
  timeout diagnostics.
- **8** Bundle-SymbolicName resolution and `(bundle, subservice)` mapping in `AEM-CLOUD-003`.
- **9** `@Via`/`@Exporter` handling, multiline `data-sly-use`.
- **10** Comment-aware code-line counts, block fingerprints, both-path messages.
- **11** `RunmodeResolver` (config < config.author < config.author.dev).
- **12** `AnalysisContext.changedFiles` pre-filters parsing; CLI resolves the git diff before analysis.
- **13** `.aem-analyser.yml` (`AnalyserConfig`/Loader), CLI `--config`, Maven `<configFile>`.
- **14** `--baseline-report` keeps suppressed findings at INFO; Maven `<baselineReport>`.
- **15** `AEM-DEAD-005` orphaned clientlib categories (platform categories exempt).
- **16** `AEM-CLOUD-005` invalid runmode folders.
- **17** `AEM-SEC-002` open redirect/SSRF, `AEM-PERF-004` unbounded query, `AEM-QUAL-006`
  resolver/session fields, `AEM-CLOUD-004` deprecated API imports.
- **18** `HistoryStore` (`runs.jsonl`), CLI `--history`/`--history-limit`, HTML run-history table.
- **19** `release-central` profile (sources/javadoc/GPG/Central/SBOM), `api-check` profile, SCM/developers.

Limitations: timeout covers the Java parse phase; the incremental cache is a changed-file prefilter
rather than a cross-run parse cache; `@Via`/`@Exporter` are recognised but not fully modelled as graph
edges; clientlib detection is a static text heuristic (INFO severity).
