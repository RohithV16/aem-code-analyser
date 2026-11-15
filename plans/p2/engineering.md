# O1 — Parallelize parsing and scanning

Status: Proposed
Priority: P2
Area: aem-analyser-core / `api.DefaultAemCodeAnalyser`

Problem / opportunity: Only `parseJavaSourcesParallel` is parallel. `parseOsgiConfigs`, the
`HtlScanner`/`JcrComponentScanner` loop (l.110-115), `PmdCpdDuplicationEngine` and
`SonarJavaScanner.scan()` are sequential; `workerThreads()` is otherwise unused.

Desired behavior: All file-level extraction uses one bounded pool; module-level work is skipped for
classifications that cannot contain the artifact.

Files to change: `api.DefaultAemCodeAnalyser.java`, `discovery/HtlScanner.java`,
`discovery/JcrComponentScanner.java`, `discovery/PmdCpdDuplicationEngine.java`,
`sonar/SonarJavaScanner.java`; new `util/ParallelExecutor.java` (virtual threads via
`Executors.newVirtualThreadPerTaskExecutor()` on JDK 21).

Implementation steps:
1. Extract the pool lifecycle into `ParallelExecutor` (submit, collect, propagate first error,
   always `shutdown()`), reuse it for all four phases.
2. Run HTL/JCR scans only for `APPS_MODULE`, `CONTENT_MODULE`, `CONTAINER_MODULE`, `BUNDLE_MODULE`
   (skip `DISPATCHER`, `PIPELINE`, `FRONTEND`); run config parsing only for modules with
   `osgiConfigPaths()` non-empty (already checked) and skip `STRUCTURE`.
3. Keep deterministic result order: collect into per-input indexed arrays, then append in input order.
4. SonarJava: parallelize per file, keep one `SonarJavaScanner` per thread group if the underlying
   API is not thread-safe (verify with a stress test before shipping).
5. Bound the queue (`Semaphore` or fixed pool size = `workerThreads()`), never virtual-thread unbounded
   when parsing large files.

Tests to add: `--threads 1` vs `--threads 8` produce identical SARIF; a `TimeLimiter` test asserting
no thread leak after analysis (`Thread.activeCount` before/after); a test that a single failing file
still yields results from the others (with an E11 diagnostic).

Acceptance criteria: Wall-clock on the portfolio repo improves measurably; results are
byte-identical across thread counts; no `ExecutorService` is left running.

Risks / notes: `PmdCpdDuplicationEngine` runs inside PMD's own analyzer and may not be safely
parallelizable per call — parallelize files, not matches. SonarJava's `JavaFrontend` is the riskiest
piece; keep it on a single thread if the stress test shows contention.

# O2 — Cache `collectModules` and avoid repeated `Files.walk`

Status: Proposed
Priority: P2
Area: aem-analyser-core / `discovery.ProjectDiscoveryEngine`, `discovery.OsgiConfigScanner`,
`cli.AnalyserCli`

Problem / opportunity: `ProjectDiscoveryEngine.buildDescriptor` calls `collectModules(model)` twice
and `classify(...)` a third time; `ModuleClassifier.hasJcrSubdirectory` re-lists directories per
call; `AnalyserCli.projectHasJacoco` walks and reads every POM under `root` (depth 4) on every run;
`findExistingCoverage` walks again.

Desired behavior: One module-list computation per POM, one directory listing per module, one POM walk
per CLI invocation.

Files to change: `discovery/ProjectDiscoveryEngine.java`, `discovery/ModuleClassifier.java`
(accept a precomputed `Set<String> jcrRootChildren`), `cli/AnalyserCli.java` (memoize POM text reads
in a `Map<Path,String>`).

Implementation steps:
1. Compute `List<String> modules = collectModules(model)` once in `buildDescriptor`, pass
   `!modules.isEmpty()` and the list to the classifier input.
2. Add `ClassificationInput.jcrRootChildren` (defaulted) and fill it from a single `Files.list`.
3. In the CLI, read POM files once into a `Map<Path, String>` shared by `projectHasJacoco`,
   `findJavaTestModules` and `findExistingCoverage`.
4. Replace `Files.walk(root, 4)` in `findExistingCoverage` with a targeted check of the discovered
   module directories.

Tests to add: a counting `FileSystem`-style test is overkill — instead assert call counts via a
package-private counter in tests, or use a fixture with a known number of POMs and assert the
diagnostic/timing. At minimum: `ProjectDiscoveryEngineTest` fixture with nested profiles asserts the
module list once (regression for E8 + O2).

Acceptance criteria: Discovery on a 40-module reactor performs no more than one `Files.walk` per
module directory depth and one `collectModules` per POM (verified by a temporary counter during
review).

Risks / notes: Do not cache across runs (stale POMs). Keep the cache local to a method scope.

# O3 — Streaming and size guards for large files

Status: Proposed
Priority: P2
Area: aem-analyser-core / `parser.config.XmlOsgiConfigParser`, `discovery.HtlScanner`,
`discovery.JcrComponentScanner`, `parser.java.JavaSourceParser`

Problem / opportunity: Every parser does `Files.readString` (whole file in memory);
`DefaultAemCodeAnalyser.parseJavaSourcesParallel` walks every `.java` file with no size cap;
`OsgiConfigScanner` stats each candidate. A `dam` content package or a vendored minified JS file can
blow the heap.

Desired behavior: StAX for `.content.xml`, a configurable `--max-file-size` (default 10 MiB) with a
diagnostic on skip, explicit binary-extension skips, and no full-file read for XML.

Files to change: `parser.config.XmlOsgiConfigParser.java` (StAX, disable DTD/external entities),
`discovery/HtlScanner.java` (streaming line reader already; add size guard), `discovery/JcrComponentScanner.java`
(StAX instead of `readString`), `api/AnalysisContext.java` (`maxFileSizeBytes`),
`api/DefaultAemCodeAnalyser.java` (guard before submitting).

Implementation steps:
1. Add `AnalysisContext.maxFileSizeBytes` (default 10 MiB) + CLI `--max-file-size` + Mojo param.
2. Guard in the three scanning entry points with `Files.size` before reading; emit
   `FILE_TOO_LARGE` diagnostic (E11).
3. Rewrite `XmlOsgiConfigParser` with `XMLInputFactory` (`SUPPORT_DTD=false`,
   `IS_SUPPORTING_EXTERNAL_ENTITIES=false`) and root-element attribute capture.
4. Add an extension skip list for `*.min.js`, `*.map`, `*.jar`, `*.zip`, `*.png|jpg|gif|woff2`.
5. Keep `generateHtml`-style API compatibility (take `Path` + `Reader`).

Tests to add: 11 MiB synthetic file → skipped + diagnostic (no OOM); malformed XML with an XXE
payload → no external fetch, no exception; `.content.xml` with 50k attributes parses under a memory
assertion; binary extension fixtures are ignored.

Acceptance criteria: A fixture with a 20 MiB `.content.xml` does not exceed the guard; no
`Files.readString` remains in the XML/JCR scanners.

Risks / notes: JavaParser still needs the full source in memory; the size guard is the mitigation.
Coordinate the guard value with O5 timeouts.

# O4 — Deterministic output and cross-platform paths

Status: Proposed
Priority: P2
Area: aem-analyser-core (all exporters), `graph`, `discovery`, `cli`, both Mojos

Problem / opportunity: `AnalysisReport.scanStatistics` is `Map.copyOf` (unordered iteration);
`PmdCpdDuplicationEngine.relativize` does not replace `\\`; `AemDup002Rule`/`ReportMojo` iterate
`HashMap`/`LinkedHashMap` inconsistently; `HtmlReportExporter` embeds `Instant.now()`; JSON is
written by Jackson with insertion order (fine) but nothing enforces it.

Desired behavior: Two runs on the same inputs produce byte-identical SARIF/Sonar/HTML; every path in
a report uses `/`.

Files to change: `model/report/AnalysisReport.java` (`TreeMap`/`LinkedHashMap` with sorted keys),
`model/report/Violation.java` (no change), `discovery/PmdCpdDuplicationEngine.java` (path
normalisation), `rules/impl/*` (never iterate `HashMap`), `export/*` (inject `Clock`), `cli`/Mojos
(sort module lists).

Implementation steps:
1. Add `util/Paths.normalize(Path root, Path file)` returning a `/`-separated, reactor-relative
   string, and use it everywhere instead of 8 copies of the same 2-line lambda (there are copies in
   `AemDead001Rule`, `002A`, `002B`, `003A/B/C`, `AemDup002Rule`).
2. Replace `HashMap` grouping in `AemDup002Rule`/`AemDup001Rule`/`AemDup003Rule` with `TreeMap` or
   sort before emitting.
3. Sort `scanStatistics` by key when constructing the report.
4. Inject `Clock` into `HtmlReportExporter` (E15) and use a fixed clock in tests.
5. Add a `--deterministic` (or always-on) sort of `formats` output order.

Tests to add: `testDeterministicSarif` (two runs, identical bytes), `testScanStatisticsOrder`,
`testWindowsStylePathNormalisation` (feed `C:\repo\a\b.java`-style strings through the normaliser),
`testDup002StableOrder`.

Acceptance criteria: Golden-file exporters pass on macOS and on a Windows CI job.

Risks / notes: Sorting by message text can reorder related locations; sort on
`(file, line, ruleId, subtype, message)` to stay stable. Do not sort `relatedLocations` — order is
semantic.

# O5 — Cancellation, timeouts, OOM-safe limits, structured diagnostics

Status: Proposed
Priority: P2
Area: aem-analyser-core / `api.DefaultAemCodeAnalyser`; CLI; Mojos

Problem / opportunity: There is no timeout, no cancellation and no memory guard. A pathological PMD
or SonarJava run hangs the build; `Future.get()` is called without a timeout
(`parseJavaSourcesParallel` l.193).

Desired behavior: `--timeout <duration>` bounds the whole analysis (and per-phase), Ctrl-C cancels
cleanly, `--max-file-size` (O3) plus a max-violations cap prevent OOM, and every limit produces a
diagnostic instead of an exception.

Files to change: `api/AnalysisContext.java` (`timeout`, `maxViolations`), `api/DefaultAemCodeAnalyser.java`
(deadline + `Future.get(timeout, MILLISECONDS)`), `cli/AnalyserCli.java` (`--timeout`,
`--max-violations`, shutdown hook), `maven/*Mojo.java` (`<timeout>`).

Implementation steps:
1. Add a `Deadline` value (start + budget) and check it between phases; abort with a partial report
   plus `ANALYSIS_TIMEOUT` diagnostic and exit code 2 (documented).
2. Replace `future.get()` with `get(remaining, MILLISECONDS)`; on timeout cancel the futures and
   `shutdownNow()`.
3. Cap accumulated violations (`maxViolations`, default 100k) with a truncation diagnostic; exporters
   already handle the list.
4. Register a JVM shutdown hook that calls `analysisHandle.cancel()`; make the handle an
   `AutoCloseable`.
5. Maven: `threadSafe = true` only after this lands (currently `CheckMojo.threadSafe = false`).

Tests to add: a fixture that sleeps in a fake parser (inject a slow `JavaSourceParser`) and asserts
timeout → partial report + diagnostic + exit 2; cancellation test via `Future.cancel`; max-violations
cap test.

Acceptance criteria: No analysis can exceed the configured budget by more than one phase; a timeout
still writes SARIF with what was collected.

Risks / notes: Partial results must be clearly marked (report `scanStatistics.truncated = 1` +
diagnostic) so CI cannot mistake a timeout for a clean build.

# O6 — Build hygiene, static analysis, release engineering

Status: Proposed
Priority: P2
Area: root `pom.xml`, module POMs, `.github/workflows`

Problem / opportunity: No enforcer, no formatter/linter, no reproducible build, no SBOM, no API
compatibility gate, and the release profile exists only in `docs/MAVEN_CENTRAL_PUBLISHING_GUIDE.md`.
CI is a single `mvn clean verify` on one OS/JDK.

Desired behavior: Failing build on Java/Maven mismatch, formatting drift, binary-incompatible core
changes; reproducible artifacts; SBOM per release; a matrix CI.

Files to change: `pom.xml`, `aem-analyser-*/pom.xml`, `.github/workflows/ci.yml`,
`.github/workflows/release.yml`, new `LICENSE` reference in `<licenses>` (C9).

Concrete changes (pin in `pluginManagement`):
- `maven-enforcer-plugin` 3.5.0 + `extra-enforcer-rules` 1.9.0: `requireJavaVersion [21,22)`,
  `requireMavenVersion [3.9.0,4.0.0)`, `banDuplicatePomDependencyVersions`,
  `requirePluginVersions`, `enforceBytecodeVersion` for `release 21`.
- `spotless-maven-plugin` 2.43.0 with `googleJavaFormat("1.22.0")`, `removeUnusedImports`,
  `importOrder`, `endWithNewline`; bound to `process-sources`, `ratchetFrom origin/main`.
- `japicmp-maven-plugin` 0.23.1 (`com.github.siom79.japicmp`) on `aem-analyser-core` only, comparing
  against the last released version, `breakBuildOnBinaryIncompatibleModifications=true` in the
  release profile.
- `cyclonedx-maven-plugin` 2.8.1 (`makeAggregateBom`, `outputFormat json` + `xml`) attached to
  `verify` in the release profile.
- Reproducible builds: `<project.build.outputTimestamp>` fixed (SCM-based via
  `git.commit.time`), `maven-jar-plugin` 3.4.1 with `reproducibleFileOrder`.
- Release profile `release-central` exactly as documented: `maven-source-plugin` 3.3.1,
  `maven-javadoc-plugin` 3.8.0, `maven-gpg-plugin` 3.2.4,
  `central-publishing-maven-plugin` 0.5.0 (`autoPublish=false`).
- `<licenses>` + `<inceptionYear>` + `<developers>`/`<scm>` (Central requires all four).
- `maven-invoker-plugin` 3.7.0 for E13 ITs, activated by `-Prun-its`.
- `maven-failsafe-plugin` 3.2.5 for the ITs; keep surefire 3.2.5.

Test matrix (`.github/workflows/ci.yml`):
- `os: [ubuntu-latest, macos-latest, windows-latest]` × `java: [21, 25]` for `mvn -B -ntp verify`
  (Windows only on 21 to keep the matrix small).
- Jobs: `verify` (matrix), `spotless` (`mvn spotless:check`), `enforcer` (implicit in verify),
  `it` (`mvn -Prun-its verify` on ubuntu), `self-scan` (build the CLI, run it on this repo with
  `--format SARIF,HTML`, upload SARIF via `github/codeql-action/upload-sarif@v3`), `sbom` (release
  profile only).
- Cache `~/.m2/repository` via `actions/setup-java@v4` `cache: maven` (already used).
- `release.yml`: tag-triggered, `mvn -B -ntp deploy -P release-central -DskipTests`.

Acceptance criteria: `mvn -B verify` fails on a formatting violation and on JDK 17; two builds from
the same commit produce identical JAR hashes; release publishes sources, javadoc, SBOM, signatures.

Risks / notes: `googleJavaFormat` will reformat the whole codebase on first run — land it as a single
mechanical commit, then add `ratchetFrom` so future PRs only check changed files. `japicmp` must be
skipped when no previous release exists (`<skip>` on the initial tag).

# O7 — Test expansion

Status: Proposed
Priority: P2
Area: all modules (`src/test`)

Problem / opportunity: 26 test classes, mostly one happy-path fixture per rule. No
`ProjectDiscoveryEngine` test, no exporter schema test, no Invoker IT, no property-based tests, no
golden TP/FP corpus. `GitDiffResolverTest.testCurrentGitRepositoryResolution` depends on this repo's
own git state.

Desired behavior: Every rule has TP and FP fixtures; every parser is parameterized; PID/normalizer/
placeholder logic is property-tested; exporters are schema-validated; Maven goals are IT-tested.

Files to change: `aem-analyser-core/src/test/java/...`, new
`aem-analyser-core/src/test/resources/fixtures/{tp,fp}/<RULE-ID>/…`,
`aem-analyser-maven-plugin/src/test/resources/it/…`.

Implementation steps (priority order):
1. Shared `RuleFixture` harness: builds an in-memory `RuleContext` from source strings, so rule tests
   stop duplicating graph construction (see `DuplicateRulesTest`/`AemQualRulesTest`).
2. Golden corpus: `fixtures/tp/<RULE>/` and `fixtures/fp/<RULE>/` as small Maven reactors; one
   parameterized test per rule asserting exact `(file, line, subtype)` triples.
3. Parser parameterized tests: a `@CsvSource` of `(fileName, content, expectedKey, expectedType,
   expectedValue)` per format, including BOM, CRLF, duplicates and malformed input.
4. Property-based tests (`junit-quickcheck` 1.0 or `jqwik` 1.9.x) for `MetatypeNameNormalizer`
   (round-trip `normalize ∘ denormalize`), `ConfigFilePidResolver` (never throws, never returns an
   empty PID) and `ConfigKeyParser`.
5. Schema validation for SARIF/Sonar (E10) plus HTML self-containment/determinism (E15).
6. Maven Invoker ITs (E13).
7. SonarJava: keep the existing scanner tests, add a rule-filter test and a stopword list to avoid
   asserting on SonarJava-version-specific messages.

Acceptance criteria: `mvn verify` runs > 200 tests; every `RuleId` has at least one TP and one FP
fixture; no test depends on the developer's git state or absolute paths.

Risks / notes: Golden fixtures must be small (one Java file per case) or CI time explodes. Pin
fixtures against `RuleId` ids, not class names, so renames do not break the corpus. Add
`assertAll`-based diagnostics so a failure names the offending fixture.

# O8 — Documentation sync and project governance

Status: Proposed
Priority: P2
Area: `README.md`, `PROJECT.md`, `docs/`, new `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md`,
`docs/adr/`

Problem / opportunity: README documents non-existent parameters (C7) and a rule table that is not
generated from `RuleId`; `PROJECT.md` describes Phase-1 behaviour that has since diverged (e.g. it
does not mention `--fix`, Sonar, PMD CPD or the HTML report); there is no CONTRIBUTING/SECURITY/
CHANGELOG and no ADR trail for rule semantics.

Desired behavior: Docs are generated or verified, not hand-maintained; every rule has a page and an
ADR when its semantics are non-obvious.

Files to change: `README.md`, `PROJECT.md`, `docs/*`, new `docs/rules/<RULE-ID>.md` (one per rule),
new `docs/adr/NNNN-*.md`, `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md` (Keep a Changelog 1.1.0),
`plans/README.md` status board.

Implementation steps:
1. Add a `maven-antrun`/`exec` step (or a small JBang/`exec-maven-plugin` goal) that renders the rule
   table in `README.md` from `RuleRegistry` (F3) between `<!-- BEGIN RULES -->` markers; fail
   `verify` when the generated block differs.
2. Add a doc test that parses every `<configuration>` block and `--flag` in the README and asserts it
   exists in `CheckMojo`/`ReportMojo`/`AnalyserCli` (this alone would have prevented C7).
3. Write the rule pages: semantics, subtypes, TP/FP fixture paths, remediation, references
   (DS 1.4 §, Metatype 1.4 §, AEM docs).
4. ADRs for the five decisions that keep coming back: PID `$` expansion, legacy hyphen factories,
   runmode scoping, `AEM-DUP-010` single producer (C5), and "test classes count as consumers".
5. `CONTRIBUTING.md`: build/test commands, fixture conventions, "run the portfolio validation",
   commit/PR checklist, `spotless:apply` requirement.
6. `SECURITY.md`: supported versions, private disclosure address, the fact that the tool reads
   source and never executes project code (except `--collect-coverage`, which runs `mvn test` —
   call that out explicitly).
7. `CHANGELOG.md`: seed with the P0/P1 waves, `Unreleased` for everything not yet released.

Acceptance criteria: CI fails when README drifts from the CLI/Mojo contract or when a `RuleId` has no
docs page; `plans/README.md` status board matches the files that actually exist.

Risks / notes: The doc-verification test must parse source annotations, not javadoc HTML, or it will
be brittle. `--collect-coverage` executing Maven is a genuine security consideration for CI — document
it before someone enables it on untrusted PRs.

---

## Implementation notes (wave 6)

- **O3 (partial):** Java/HTL/JCR files larger than 10 MiB are skipped with a `FILE_TOO_LARGE`
  diagnostic; analyser/scanner APIs gained `DiagnosticSink` overloads. StAX rewrite and a
  configurable CLI flag remain.
- **O4 (partial):** `AnalysisReport.scanStatistics` is stored in a `TreeMap` so iteration/JSON key
  order is deterministic. Golden cross-platform path tests remain.
- **O8 (partial):** Added `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md` and synced README/baseline
  docs. Rule-per-page docs and ADRs remain.
