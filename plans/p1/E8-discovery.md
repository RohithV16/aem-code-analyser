# E8 — Reactor discovery and module classification edge cases

Status: Done (core semantics)
Priority: P1
Area: aem-analyser-core / `discovery.ProjectDiscoveryEngine`, `discovery.ModuleClassifier`

Problem / opportunity:
Wave-2 status (2026-11-15) — **profile semantics and caching implemented**:
- `collectModules` now disables `activeByDefault` profiles for a POM as soon as any profile of that
  POM is activated explicitly (Maven semantics), de-duplicates module ids, and is computed once per
  POM and reused by child traversal and classification (also closes O2).
- Remaining E8 work: activation by property/JDK/file/OS, `-P!p`/`-P?p` handling, `jar` classification
  cleanup, `pom`-without-modules fallback, and `conf`-only content packages.

Already handled (verified — do not re-implement):
- Nested aggregators at arbitrary depth via a BFS queue with a `visited` set.
- Module entries that point either at a directory or directly at a `pom.xml`.
- Container detection by `filevault` embeddeds, `ui.config` by valid OSGi configs, then naming
  conventions, then `packageType`, then physical `jcr_root` subdirectories.
- `it.tests` / `it-tests` / `.tests` / `cypress` / `-tests` detection; frontend detection via
  `package.json` / `package-lock.json`.
Real gaps:
1. **Profile semantics**: `collectModules` (l.104-117) adds `activeByDefault` profile modules
   *unconditionally* and then also adds explicitly requested profiles. Maven disables
   `activeByDefault` in the same POM as soon as any profile is activated explicitly, so the module
   set can contain modules Maven would not build.
2. Profile activation by property/JDK/file/OS is not evaluated (only `id` matching).
3. Negated/deactivated profiles (`-P!p`, `-P?p`) are not understood; `activeProfiles.contains(id)`
   would treat `!p` as a literal id.
4. `collectModules` is called three times per POM (`buildDescriptor` twice + classification) — O2.
5. **`jar` is always `BUNDLE_MODULE`**: `ModuleClassifier` l.52-55 has two branches that return the
   same value, so `hasBndPlugin`/`dependsOnAemSdk` are dead there and `it.tests` jars *with* a bnd
   plugin become bundles.
6. `pom`-without-modules falls through to `PIPELINE_MODULE` even for a POM named `all` or
   `*-parent`; a non-aggregator POM is emitted as a module descriptor regardless.
7. Enterprise layouts not covered: `config` folders under `src/main/content/jcr_root/conf` (ACL
   content packages with no `apps`), `ui.apps.structure` (contains `jcr_root` but no `apps`),
   `<app>.all` containing both `embeddeds` and modules, `ui.frontend` with only `dist/`.

Desired behavior:
- Discovery mirrors `mvn -P<profiles>` module selection, including `activeByDefault` suppression.
- Classification is decidable from a small, documented set of rules; no branch returns a constant.

Files to change:
- `core/discovery/ProjectDiscoveryEngine.java` — profile evaluation helper, single `collectModules`
  call per model, `pom`-without-modules handling.
- `core/discovery/ModuleClassifier.java` — remove the dead `jar` branch, extend `structure`/`all`
  heuristics, add `conf`-only content packages.
- `core/model/discovery/ModuleDescriptor.java` — optionally record `activeProfiles` for diagnostics.

Implementation steps:
1. Add `resolveActiveProfiles(Model, List<String> requested)` returning the effective profile ids:
   explicit matches win; `activeByDefault` profiles are included only when no profile of that POM is
   explicitly activated; ignore `!`/`?` prefixes with a diagnostic instead of a literal match.
2. Cache the `collectModules(model)` result in `buildDescriptor` (also unblocks O2).
3. Remove `if (packaging.equals("bundle") || …) return BUNDLE_MODULE; return BUNDLE_MODULE;` and
   replace with an explicit decision + comment.
4. Treat a `pom` POM with no modules, no frontend build, no dispatcher/pipeline hint and an
   artifactId ending in `-parent` as `AGGREGATOR_MODULE` (non-buildable parent) rather than
   `PIPELINE_MODULE`.
5. Add a `conf`-only content package heuristic (`src/main/content/jcr_root/conf` exists, no `apps`).

Tests to add:
- `ProjectDiscoveryEngineTest` (new) with `@TempDir` reactor fixtures:
  `activeByDefault` + explicit `-Pother` (module excluded);
  `-P!p` (p only, diagnostic recorded);
  nested aggregator two levels deep;
  `pom` parent with no modules.
- `ModuleClassifierTest` additions: `it.tests` jar with bnd plugin, `ui.apps.structure`,
  `*-parent` POM, `conf`-only package.

Acceptance criteria:
- The discovered module list for the fixtures equals the list `mvn help:evaluate` would build.
- No `ModuleClassification` value is produced by an unconditionally-true branch.
- Existing discovery-dependent tests (`EndToEndAnalysisTest`, `ModuleClassifierTest`) stay green.

Risks / notes:
- Profile activation by property cannot be evaluated without a full Maven session; the Maven plugin
  could pass `session.getRequest().getActiveProfiles()` (also in E13) — prefer that over guessing.
- Over-tightening `pom` classification can drop `pipelines` POMs; keep the dispatcher/pipeline
  path/name hints ahead of the parent heuristic.

---

## Implementation

- `activeByDefault` profiles are disabled per POM when any profile of that POM is explicitly
  activated; modules are de-duplicated and collected once per POM (also O2).
- Plain `jar` modules without bnd/AEM SDK are `UNCLASSIFIED` instead of pretending to be bundles.
- A `pom` module without children whose artifactId ends in `-parent`/`.parent` is an aggregator.
- Content packages with only `jcr_root/conf` are classified as `ACL_MODULE`.
- Remaining: activation by property/JDK/file/OS and `-P!p`/`-P?p` handling.
