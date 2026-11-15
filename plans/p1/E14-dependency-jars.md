# E14 — `dependencyJarRoots` default and `--dependency-jars`

Status: Done
Priority: P1
Area: aem-analyser-core / `api.AnalysisContext`, `api.DefaultAemCodeAnalyser.indexDependencies`,
`parser.bytecode.DependencyBytecodeIndexer`; `cli.AnalyserCli`; both Mojos

Problem / opportunity:
- `AnalysisContext` javadoc (l.13) says `dependencyJarRoots` "defaults to the local Maven
  repository". Verified: nothing sets it. `AnalysisContext.of()` passes `List.of()`, the CLI never
  calls `dependencyJarRoots(...)`, and both Mojos use `AnalysisContext.of(root)`.
- Therefore `indexDependencies` always indexes zero JARs, `DependencyIndex.getComponentPids()` is
  always empty, and `RuleContext.isExternalPid` is always false. `DependencyBytecodeIndexer` is dead
  code in every shipped entry point, and `AEM-DEAD-001`'s external-PID suppression (E1) is inert.
- The indexer walks every JAR under each root (`Files.walk`) and ASM-scans every class with no cache,
  no size cap and no cancellation — pointing it at `~/.m2/repository` as-is would be very slow.
- `Ignore`-policy PIDs from dependencies (`registerIgnoredPolicy`) are the other half of the
  suppression story and are similarly unused.

Desired behavior:
- The javadoc matches reality, or reality matches the javadoc — pick one and test it.
- Users can point the analyser at JAR roots explicitly, with bounded cost.

Files to change:
- `core/api/AnalysisContext.java` — javadoc correction; document that the value is opt-in.
- `core/api/DefaultAemCodeAnalyser.java` — guard the walk (max JARs, max bytes, skip
  `-sources`/`-javadoc`), report progress/diagnostics (E11).
- `cli/AnalyserCli.java` — `--dependency-jars` (repeatable, comma-split, `Path`),
  `--no-dependency-index`.
- `maven/AbstractAnalyserMojo.java` (E13) — `<dependencyJars>` plus optional
  `${settings.localRepository}` default.

Implementation steps:
1. Decide the contract: **opt-in only**. Reword the javadoc to "additional JAR roots …; empty by
   default", and add a `DependencyScope` note explaining that an empty list disables external PID
   detection.
2. Add the CLI option and map to `builder.dependencyJarRoots(...)`; accept files and directories.
3. Add filtering in `indexDependencies`: skip names matching `-(sources|javadoc|tests)\.jar$`, cap
   the number of JARs (default 500) and total bytes (default 512 MiB), and record a diagnostic when
   capped.
4. Add a content cache keyed by `(absolutePath, size, lastModifiedTime)` so repeated runs in one JVM
   (Maven reactor) do not rescan.
5. Maven: pass `${settings.localRepository}` only when the user opts in
   (`<dependencyJars><dependencyJar>${settings.localRepository}</dependencyJar></dependencyJars>`),
   never by default.

Tests to add:
- `DependencyBytecodeIndexerTest` (new): build a tiny JAR in `@TempDir` containing a class annotated
  `@Component(configurationPid="x.y.Z")` (compile with the in-repo `javax.tools.JavaCompiler` or ship
  a pre-built test resource) and assert `getComponentPids()` contains it.
- `DefaultAemCodeAnalyserTest`: with a JAR root supplied, an `x.y.Z.cfg.json` does **not** produce
  `AEM-DEAD-001`; without it, it does.
- CLI option-registration test for `--dependency-jars`.
- Cap test: 501 JARs → diagnostic, no exception.

Acceptance criteria:
- Javadoc, CLI help and Maven docs describe the same default (empty).
- External PID suppression demonstrably works in an end-to-end fixture.
- Indexing an empty list costs nothing (no walk, no exception).

Risks / notes:
- Scanning all of `~/.m2/repository` is not a safe default; do not enable it implicitly even though
  the javadoc promised it. Fix the doc (cheaper, no regression) and make the feature explicit.
- `registerIgnoredPolicy` is consumed by nothing today; wire it into `AEM-DEAD-001` in the same
  change or leave it documented as unused (do not delete — it is the natural input for C8/E1).

---

## Implementation

- Javadoc corrected to "empty by default".
- CLI `--dependency-jars` (comma-separated files/directories) and Maven `<dependencyJars>`.
- Dependency indexing reports unreadable JARs and malformed classes through `DiagnosticSink`.
- A size/count cap and content cache remain follow-ups (O5).
