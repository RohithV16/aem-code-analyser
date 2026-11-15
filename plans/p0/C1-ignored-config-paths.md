# C1 — `ignoredConfigPaths` is declared but never consumed
Status: Done (wave 1, 2026-11-15)
Priority: P0
Area: aem-analyser-core (`core.api`, `core.discovery`, `core.util`), aem-analyser-cli (`cli`), aem-analyser-maven-plugin

Implementation note: landed as `core.util.PathPatternMatcher` (not `ConfigPathFilter`), wired in
`DefaultAemCodeAnalyser.parseOsgiConfigs`, CLI `--ignore-config`, and the shared
`AbstractAnalyserMojo.ignoredConfigPaths` Maven parameter. Tests: `PathPatternMatcherTest` and
`EndToEndAnalysisTest.ignoredConfigPathsExcludeMatchingConfigFiles`.

Verified evidence:
- `aem-analyser-core/src/main/java/io/github/rv16/...` (actual path: `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/api/AnalysisContext.java`) — the record component `ignoredConfigPaths` exists at line 25, is defensively copied at line 34 (`Set.copyOf(...)`), and has a builder setter at lines 100-102. Its javadoc (line 15) already promises "config file path patterns (reactor-relative, glob) excluded from analysis".
- `core/api/DefaultAemCodeAnalyser.java:214-234` — `parseOsgiConfigs(AnalysisContext, List<ModuleDescriptor>)` iterates `module.osgiConfigPaths()` (line 223) and calls `parser.parse(configPath, runmodes)` (line 227) for every file. `context.ignoredConfigPaths()` is never read; the only `context.` reads in the method are unused arguments.
- Repo-wide grep for `ignoredConfigPaths` returns hits only in `AnalysisContext.java` plus `plans/*.md` — i.e. zero production call sites, zero tests.
- `core/discovery/OsgiConfigScanner.java:49-63` — `scanModule(Path)` walks the osgiconfig tree and has no ignore predicate; `ProjectDiscoveryEngine.java:140` populates `ModuleDescriptor.osgiConfigPaths` from it.
- `aem-analyser-cli/src/main/java/.../AnalyserCli.java:77-79` exposes only `--ignored-services`; there is no `--ignore-config`/`--ignore-config-path` option, and `run()` (lines 150-164) never sets `builder.ignoredConfigPaths(...)`.
- `aem-analyser-maven-plugin/src/main/java/.../CheckMojo.java:32-47` and `ReportMojo.java:30-47` declare only `executionRoot`, `projectRoot`, `failOnError`/`failOnWarning`/`skip`, `formats`, `project`, `session` — no ignored-config parameter; both build the context with `AnalysisContext.of(root)` (CheckMojo:76, ReportMojo:68), which hardcodes the empty set.

Problem: `ignoredConfigPaths` is a public API option that silently does nothing. Real AEM reactors contain vendor/generated configs under `ui.config`/`all` that a team legitimately wants excluded; today the only workaround is editing files or accepting permanent `AEM-DEAD-*` findings. Because the flag is accepted by the record/builder, callers get a false sense of control (and the Maven/CLI surface has no way to pass it at all).

Desired behavior:
- Patterns are globs matched against the reactor-relative path with `/` separators (e.g. `ui.config/src/main/content/jcr_root/apps/acme/osgiconfig/config.publish/**`), with absolute-path patterns also supported.
- A matched config file is never parsed, never becomes an `OSGiConfigNode`, and therefore produces zero `AEM-DEAD-001`, `AEM-DEAD-002A`, `AEM-DUP-002` or other config-derived findings; it is not counted in the `osgiConfigs` statistic.
- CLI `--ignore-config` (comma-separated, repeatable) and Maven `<ignoredConfigPaths>` on both `check` and `report` pass the patterns through.
- Empty/unset patterns preserve today's behavior exactly.

Files to change:
- `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/util/PathPatternMatcher.java` (new, landed) — compile patterns once, normalize separators, match reactor-relative path, absolute path or bare file name.
- `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/api/DefaultAemCodeAnalyser.java` — build the matcher from `context.ignoredConfigPaths()` and skip matched paths in `parseOsgiConfigs` (landed).
- `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/discovery/OsgiConfigScanner.java` — optional future `scanModule(Path, Predicate<Path>)` overload so discovery and parsing can share one filter definition.
- `aem-analyser-cli/src/main/java/io/github/rohithv16/aem/analyser/cli/AnalyserCli.java` — `--ignore-config` / `--ignore-config-path`, wired to the builder (landed).
- `aem-analyser-maven-plugin/src/main/java/io/github/rohithv16/aem/analyser/maven/AbstractAnalyserMojo.java` — shared `aem-analyser.ignoredConfigPaths` parameter for both goals (landed).
- `README.md` / `PROJECT.md` — document the option and glob semantics.

Implementation steps:
1. Add `ConfigPathFilter` in core: keep the original pattern strings, split on `,` only at the callers, normalize `\` to `/`, and match in this order: (a) reactor-relative path (`projectRoot.relativize(abs)`), (b) module-relative path is not available here, so also match the absolute normalized path when the pattern is absolute. Use `FileSystems.getDefault().getPathMatcher("glob:" + pattern)`; treat a pattern that ends with `/` as `/**`.
2. Make matching tolerant: if a pattern has no `/`, match it against the file name as a convenience (`**/name`) and document that. Keep `Set` semantics (duplicates collapse).
3. In `DefaultAemCodeAnalyser`, construct `ConfigPathFilter filter = new ConfigPathFilter(context.ignoredConfigPaths(), context.projectRoot())`; in the loop skip when `filter.isIgnored(configPath)` **before** `resolveRunmodes` (avoids work and avoids any parser diagnostics).
4. Increment a local `ignoredCount`, thread it into `scanStatistics` and emit a new `ignoredOsgiConfigs` statistic; print it only in CLI verbose output (or via the existing info line) so default output is stable.
5. CLI: add the option, set `builder.ignoredConfigPaths(...)` when non-empty. Keep picocli `split = ","` so `--ignore-config a --ignore-config b` accumulates into the set.
6. Maven: add the parameters; both mojos must stop calling `AnalysisContext.of(root)` and build the context explicitly. Keep `enableSonar` default true (C7 base class handles that).
7. Docs: add a short "Ignoring configuration files" subsection with a `ui.config/.../config.publish/**` example and a note that patterns are reactor-relative.

Tests to add:
- `aem-analyser-core/src/test/java/io/github/rohithv16/aem/analyser/core/discovery/ConfigPathFilterTest.java` — relative glob matches; absolute pattern matches; Windows-style pattern with `\` matches a `/` path; non-matching pattern returns false; bare filename pattern matches any directory; empty set matches nothing; trailing `/` behaves as `/**`.
- `aem-analyser-core/src/test/java/io/github/rohithv16/aem/analyser/core/rules/IgnoredConfigPathsAnalysisTest.java` — reuse the `EndToEndAnalysisTest` fixture style: one normally-orphaned config is ignored via a reactor-relative glob → `AEM-DEAD-001/002A` count drops by exactly one and `report.statistics().get("ignoredOsgiConfigs")` is 1; the same project without the pattern still reports the finding (proves the pattern is what changed the result).
- `aem-analyser-cli/src/test/java/.../AnalyserCliTest.java` — usage contains `--ignore-config`; `parseArgs("--ignore-config", "ui.config/**")` populates the field (package-private accessor).
- Maven plugin: add a reflection assertion (see C7's plugin test setup) that both mojos expose an `ignoredConfigPaths` field annotated with `@Parameter`.

Acceptance criteria:
- With a pattern matching a known-orphaned config file, that file produces zero violations across all rules and is absent from `osgiConfigs`/`ignoredOsgiConfigs` accounting as specified.
- Reactor-relative, absolute and Windows-separator patterns all behave identically; an empty pattern set is a no-op.
- CLI `--ignore-config` and Maven `<ignoredConfigPaths>` are documented and covered by tests.
- `mvn clean verify` green; portfolio validation shows no change when no patterns are configured.

Risks / notes:
- Do **not** filter inside rules (e.g. skip violations by path): the config must never enter the graph, otherwise `AEM-DEAD-001` will still see it and relationship edges disappear inconsistently.
- `PathMatcher("glob:...")` treats `**` as "zero or more directories" only when it is a whole segment; document that `config.*` is a literal segment, not a prefix match.
- Filtering at `DefaultAemCodeAnalyser` keeps `ProjectDiscoveryEngine` pure but means `ModuleDescriptor.osgiConfigPaths` still lists ignored files; no consumer besides `parseOsgiConfigs` uses that field today, so this is safe. If a future consumer appears, move the filter into discovery via the new scanner overload.
- Coordinate with C7/E13 so `ignoredConfigPaths` is declared once in the shared Maven base class.
