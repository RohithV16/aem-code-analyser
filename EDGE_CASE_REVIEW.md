# Edge-Case Review — AEM Code Analyser

**Version reviewed:** `1.0.0-SNAPSHOT` @ `a72b9fe` (main)
**Review date:** 2026-11-16
**Scope:** correctness of the analysis pipeline, rule set, suppression/baseline, exporters, CLI and Maven plugin.
**Not reviewed:** AEM domain modelling choices, the HTML dashboard's visual design, publishing/release mechanics beyond what affects the build.

> Nothing in this review was committed. This file is uncommitted by design.

---

## How this was produced

Three independent passes, then adversarial verification of every claim:

| Pass | What | Scale |
|---|---|---|
| 1 | Manual read of the whole pipeline (discovery → parse → graph → rules → export), plus baseline/build verification | 3 modules, 16.3k LOC main, 5.8k LOC test |
| 2 | Empirical: built `aem-analyser-cli.jar` and ran it against a real AEM archetype project (`rohith-aem-portfolio`: 57 Java files, 214 HTL, 22 OSGi configs) and against purpose-built fixtures | 22 findings on the real project, ~40 fixture projects |
| 3 | Four independent auditors (config/discovery, Java+graph, all 29 rules, delivery/exporters) re-derived findings from scratch, each required to produce a runnable reproduction | 44 candidate findings |

**Every finding marked CONFIRMED below was reproduced end-to-end.** The most damaging ones (the `--sonar` inversion, the Maven-plugin `target` corruption) were re-reproduced by the reviewer, not taken on trust. Findings that could not be reproduced are quarantined in [Suspected](#suspected--not-reproduced), and hypotheses that turned out to be **wrong** are listed in [Refuted](#refuted--checked-and-correct) so they are not re-investigated later.

Evidence artefacts from the runs live under `~/.hermes/cache/scratch/` (`aemrun/`, `verify-config/`, `verify-graph/`, `verify-rules/`, `verify-delivery/`, `mpcheck/`).

### Baseline health (verified)

```
mvn -B -ntp -o test   ->  BUILD SUCCESS, 212 tests (core 198, cli 11, maven-plugin 3)
```
The existing test suite is green and does not cover any defect below — every one of them passes the tests as written.

---

## Severity summary

| # | Severity | Count |
|---|---|---|
| P0 | Silently produces a green build, corrupts the repo, or blinds an entire rule family | 8 |
| P1 | Wrong finding (false positive / false negative) a user will hit in a normal AEM project | 20 |
| P2 | Diagnostics, UX, performance, metadata | 9 |
| — | Suspected, not reproduced | 9 |

### The eight P0s

1. `--sonar` **disables** SonarJava — passing the flag drops all 6 Sonar findings [P0-1](#p0-1--sonar-silently-disables-sonarjava)
2. The Maven plugin writes a **regular file named `target`**, breaking every later Maven build [P0-2](#p0-2--maven-plugin-writes-a-file-named-target-and-breaks-the-build)
3. `--since` with a bad ref reports **0 findings, exit 0**, claiming git filtering is active [P0-3](#p0-3--git-diff-failures-produce-a-vacuous-green-build)
4. `--changed-only` on a clean tree reports **0 findings, exit 0** [P0-4](#p0-4---changed-only-on-a-clean-tree-scans-nothing-and-passes)
5. `AEM-SEC-002`, `AEM-PERF-004`, `AEM-CLOUD-004` **never see an `@Component` or `@Model`** [P0-5](#p0-5--three-rules-can-only-ever-inspect-plain-classes)
6. `--fix` corrupts source and reports success when it wrote nothing [P0-6](#p0-6---fix-corrupts-source-and-reports-phantom-success)
7. One baselined finding suppresses **unlimited** new identical findings [P0-7](#p0-7--baseline-has-no-multiplicity-one-entry-suppresses-n)
8. Wildcard imports make `@Reference` targets resolve to the wrong package — phantom "unused service" [P0-8](#p0-8--wildcard-imports-break-service-and-model-resolution)

---

# P0 — release blockers

## P0-1 · `--sonar` silently disables SonarJava

**Where:** `aem-analyser-cli/src/main/java/.../cli/AnalyserCli.java:127-129`, `:274`
**Severity:** P0 — a whole rule family disappears exactly when a CI job asks for it.

```java
@Option(names = {"--sonar"}, defaultValue = "true")  // line 127
private boolean sonar = true;

@Option(names = {"--no-sonar"})
private boolean noSonar;
...
boolean enableSonar = sonar && !noSonar;            // line 274
```

Picocli assigns the **opposite of `defaultValue`** when a zero-arity boolean flag is present. Because the default is `true`, writing `--sonar` sets the field to `false`, and `enableSonar` becomes `false`.

**Reproduced (reviewer, on the real project):**

```
$ java -jar aem-analyser-cli.jar -p rohith-aem-portfolio --no-html
Total Violations: 22   (6 x java:S*)
$ ... --sonar
Total Violations: 16   (0 x java:S*)
$ ... --no-sonar
Total Violations: 16   (0 x java:S*)
```

`--sonar` and `--no-sonar` are indistinguishable, and both are wrong. A direct API probe (`enableSonar(true)`) returns the 8 expected findings on the same tree, so the loss is purely the flag.

**Fix:** one negatable field — `@Option(names = "--sonar", negatable = true) boolean sonar = true;` — delete `noSonar`, and add a CLI test asserting `--sonar` yields `java:*` findings.

---

## P0-2 · Maven plugin writes a file named `target` and breaks the build

**Where:** `aem-analyser-maven-plugin/src/main/java/.../maven/AbstractAnalyserMojo.java:361-370`

```java
Path target = outputFile != null ? outputFile.toPath()... : defaultOutputDirectory(root); // a DIRECTORY
Path singleFile = formats.size() == 1 ? target : null;   // treats the directory as the report file
Path directory = singleFile != null ? singleFile.getParent() : target;
...
Path file = singleFile != null ? singleFile : directory.resolve(fileName(format));
```

The default goal format is `CONSOLE` (one format) and `outputFile` is normally null, so `singleFile` is `<project>/target` and the console report is written **as a file at that path**.

**Reproduced (reviewer, fresh single-module project):**

```
$ mvn -o -B <plugin>:check            # default configuration
$ file target                          -> target: ASCII text
$ ls                                   -> pom.xml  src  target(file)  aem-analyser-report.html
$ mvn -o -B compile                    -> BUILD FAILURE
   [ERROR] error while writing demo.App: could not create parent directories
```

Effects: the first plugin run poisons the module directory, every subsequent `mvn compile/test/package` fails, and the HTML dashboard lands in the project root instead of `target/`.

**Fix:** only treat `target` as a file when `outputFile != null`; otherwise always resolve per-format filenames inside the directory. Add an IT that runs the goal with no configuration and then compiles.

---

## P0-3 · Git diff failures produce a vacuous green build

**Where:** `aem-analyser-core/.../core/git/GitDiffResolver.java:161-173`

```java
pb.redirectError(ProcessBuilder.Redirect.DISCARD);
Process p = pb.start();
... read stdout ...
p.waitFor();          // exit status never inspected
```

A failing `git diff` (exit 128) yields an empty line stream, no exception, `isGitRepo == true` and `changedFiles == {}`.

**Reproduced:** `--since does-not-exist` on a clean repo prints
`Git-diff filtering active (0 changed file(s) found)` → `Total Violations: 0` → `GATE PASSED`, exit 0. Same for `--since origin/main` on a shallow CI clone where the ref was never fetched — the most common CI topology.

**Fix:** check the exit status; on failure either exit non-zero or fall back to a full scan with a loud warning. Never proceed with a partially populated changed-file set.

---

## P0-4 · `--changed-only` on a clean tree scans nothing and passes

**Where:** `GitDiffResolver.java:39-46`, `AnalyserCli.java:460-473`

With no changed files, `AnalysisContext.changedFiles` is empty in the "nothing changed" sense, `isChanged()` returns `false` for every file, and the report is empty at exit 0 — the exact same output as a genuinely clean run. A pre-commit hook or PR job therefore passes on a tree full of ERROR findings.

**Reproduced:** clean committed tree → `0 changed file(s) found` → `Total Violations: 0`, exit 0.

**Fix:** distinguish "filter inactive" from "filter active with an empty set"; warn explicitly and, for CI, consider a non-zero exit or a `--changed-only=strict` mode.

---

## P0-5 · Three rules can only ever inspect plain classes

**Where:** `aem-analyser-core/.../parser/java/JavaSourceParser.java:119-148` (the `continue` at 125 / 134 / 143), consumed by
`AemSec002Rule.java:28`, `AemPerf004Rule.java:28`, `AemCloud004Rule.java:37` — all iterate `buildResult.classNodes().values()`.

Each type is filed into exactly **one** bucket (`ocds`, `components`, `slingModels`, else `javaClasses`) and `continue`s, so `classNodes()` contains only types that carry no AEM annotation.

**Reproduced:** twin classes in one fixture — identical `response.sendRedirect(target)` body and identical deprecated import:

| Class | Annotation | AEM-SEC-002 | AEM-CLOUD-004 |
|---|---|---|---|
| `RedirectPlain` | none | reported | reported |
| `RedirectComponent` | `@Component(...)` servlet | **silent** | **silent** |
| `LegacyModel` | `@Model` | — | **silent** |

This is the single largest silent false-negative surface in the product: servlets, event handlers and Sling Models are precisely where redirects, queries and legacy imports live.

**Fix:** iterate the union of `classNodes()`, `componentsByFqn()` and `modelNodes()` — or register components/models in `javaClasses` too — and expose one `RuleContext.allJavaTypes()` so file-scanning rules cannot drift again.

---

## P0-6 · `--fix` corrupts source and reports phantom success

**Where:** `aem-analyser-core/.../core/fix/AutoFixEngine.java:54-76`, `:78-89`, `:118-128`, `:130-158`

Four independent defects in one small class:

| Defect | Evidence |
|---|---|
| `replaceAll("\\s*==\\s*true\\b", "")` runs over the **raw file text** | `static final String DOC = "value == true enables the feature";` → `"value enables the feature"` (user-visible string silently rewritten) |
| All three transforms run on **every** candidate file, regardless of which rules fired | A file with only a duplicate import also gets the S1125 rewrite, and a `FixRecord` is emitted for a rule that never matched → "repaired 2 issue(s)" including one phantom |
| `StandardCharsets.UTF_8` is substituted, but the import is only inserted when `content.indexOf("package ") >= 0` | default-package file → `javac` error: `cannot find symbol: StandardCharsets`; the tree is left uncompilable, then re-analysed |
| `modifiedCount++` happens **before** `Files.writeString`, and `catch (IOException ignored)` | read-only file → `Auto-fix successfully repaired 2 issue(s)`, file byte-identical (md5 unchanged) |

**Fix:** drive transforms from the violation list per file; apply the boolean rewrite through the AST (or mask string literals/comments first); insert imports safely or skip the rewrite; count only after a successful write and report failures.

---

## P0-7 · Baseline has no multiplicity: one entry suppresses N

**Where:** `baseline/Fingerprint.java:21-27` (`ruleId|subtype|filePath|normalizedMessage`, no line/column), `baseline/BaselineFilter.java:28-30` (`baseline.contains(...)` against a set)

**Reproduced:** baseline saved with 1 violation, then a report containing 5 violations with the same rule/subtype/message in the same file (lines 29, 100-103) → **0 remaining**. In the real project, `--update-baseline` wrote 14 entries but only 13 unique fingerprints (the two `AEM-CLOUD-003` findings at `CursorStateServiceImpl.java:29` and `:42` collapse).

Worse, `normalize()` replaces **every digit** and **every quoted string** with `#`:

```java
.replaceAll("\\d+", "#").replaceAll("'[^']*'", "'#'")
```

so `Ghost configuration property 'foo' in 'X'` and `... 'bar' in 'X'` share a fingerprint — one accepted ghost property hides all later ones in that file.

**Impact:** newly introduced copies of an accepted problem are grandfathered forever; the CI gate can never catch a growing class of recurring defects.

**Fix:** store a `count` per fingerprint and consume occurrences (or add a per-file occurrence ordinal); narrow the normalisation to genuinely volatile parts. Separately, renames invalidate the baseline — `filePath` is part of the hash, verified: renaming one file un-suppressed 2 findings.

---

## P0-8 · Wildcard imports break service and model resolution

**Where:** `parser/java/visitor/AnnotationUtils.java:105-117` (asterisk imports skipped) and `:183-188` (fallback `packageName + "." + typeName`)

```java
for (ImportDeclaration imp : imports) {
    if (!imp.isAsterisk()) { ...map simple name -> FQN... }   // asterisks are dropped
}
...
return packageName + "." + typeName;                         // invents the consumer's own package
```

With `import com.acme.api.*;` + `@Reference private Greeter greeter;`, the target resolves to `com.acme.impl.Greeter`, which does not exist → no `INJECTS_SERVICE` edge → `AEM-DEAD-003A` "zero injections".

**Reproduced:** single-variable A/B, identical except the import form:

| Fixture | `@Reference` | Result |
|---|---|---|
| `ClockServlet` | `import com.acme.api.Clock;` | `ClockImpl` correctly considered used |
| `GreeterServlet` | `import com.acme.api.*;` | `GreeterImpl` **falsely reported unused** |

Same root cause for `adaptTo()`: wildcard variant produced `Edges: 0` + `AEM-DEAD-003B UNUSED_SLING_MODEL`; explicit variant produced `Edges: 1`, no finding.

**Fix:** keep asterisk-imported packages as candidates and resolve a simple name against the graph's class/service index within those packages; emit a diagnostic when resolution falls back to the synthetic same-package FQN.

---

# P1 — wrong findings in normal projects

## Discovery & OSGi configuration

### P1-1 · Folder-stored and `.config.xml` configs are never discovered
`discovery/OsgiConfigScanner.java` — the anchors end in `(?<filename>[^/]+)`, which cannot cross the extra directory level of the standard FileVault layout `config/<pid>.config/.content.xml`, and `.config.xml` is not in `ALLOWED_EXTENSIONS` (`:34`).
**Reproduced:** a fixture containing `config/com.example.Foo.config/.content.xml`, `config/com.example.Bar.config.xml` and `config/com.example.Baz.config` produced findings only for `Baz`.
Compounding it, `ConfigFilePidResolver.stripExtension(".content.xml")` returns `""`, so even a discovered file would fail PID validation — two independent barriers.
**Impact:** silent loss of all OSGi-config analysis for projects using those layouts; no diagnostic marks the gap.

### P1-2 · Numeric-hyphen PID is misread as a factory instance → false `AEM-DEAD-001`
`ConfigFilePidResolver.java:61-72` splits legacy `.config` names at the last hyphen and flags ambiguity only when the suffix is **non**-numeric. `<pid>-1.config`, `<pid>-2.config` therefore become factory instances of `<pid>` with `ambiguousHyphen=false`, so the graph-time disambiguation in `ReferenceGraphBuilder.toConfigNode()` (`:391-400`) never runs.
**Reproduced:** `com.example.foo-1.config` with a component whose PID is exactly `com.example.foo-1` → `AEM-DEAD-001` ERROR.

### P1-3 · Adobe's hyphen-suffixed factory PIDs are reported orphaned
`rules/catalogs/PlatformPidCatalog.java:13-33` matches exact PIDs or segment-boundary prefixes. `org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended` is in `EXACT_PIDS`, but a `.cfg.json` named `...ServiceUserMapperImpl.amended-<project>.cfg.json` (the documented AEM Cloud naming) is **not** split as a factory (only `~` is, for `.cfg.json`), so the PID keeps the suffix and fails the catalog.
**Reproduced:** fixture emitted `[ERROR] AEM-DEAD-001 ... PID org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-audit`. The `~`-suffixed control was accepted.
**Fix:** on exact/prefix failure, retry with the trailing `-<segment>` (and `~<segment>`) removed, accepting it when the base is a known factory PID.

### P1-4 · Non-dotted PIDs are dropped with no trace
`PID_PATTERN` (`ConfigFilePidResolver.java:20-21`) requires at least one dot; `OsgiConfigScanner.accept()` returns empty with no diagnostic. A config named `myconfig.config` vanishes silently, and a report with zero config findings is indistinguishable from a correct one. Rejections are never counted (`discovered` vs `parsed` vs `rejected` is not tracked anywhere).

### P1-5 · Runmode precedence is implemented but never called
`parser/config/RunmodeResolver.effectiveProperties()` has **no caller** in `src/main` (verified by grep); `ReferenceGraphIndex.getConfigsForRunmode()` likewise. Consequences:
* `AEM-DUP-002` groups on **exact** runmode-set equality (`AemDup002Rule.java:41-44`), so the classic "default config + `config.author` override of the same PID" — the dangerous case — is missed (`config/` vs `config.author/` with the same PID produced no finding), while the harmless same-folder duplicate is caught.
* Ghost-property analysis (`AEM-DEAD-002A`) evaluates each file independently and cannot see a property supplied by a higher-precedence runmode file.

**Fix:** merge per PID by precedence before rule evaluation, or delete `RunmodeResolver` and document that runmode merging is out of scope.

## Java parsing & graph

### P1-6 · An OCD pid that equals another OCD's FQN drops the second OCD
`graph/ReferenceGraphIndex.java:48` puts the OCD under both its FQN and its pid **in one map**; `ReferenceGraphBuilder.java:78` then skips any OCD whose FQN is already a key.
**Reproduced:** `AlphaConfig` declaring `@ObjectClassDefinition(pid="com.acme.cfg.BetaConfig")` parsed first → `BetaConfig` never registered. Output: `AEM-DEAD-001` for Beta's config pointing `Related:` at **Alpha's** file, and `AEM-DEAD-002A` flagging Beta's real properties as ghosts against Alpha. Silent and file-order dependent.
**Fix:** separate `ocdByFqn` / `ocdByPid` maps.

### P1-7 · `@Reference` in a non-component base class is discarded
`JavaSourceParser.java:122-148` only emits `PendingServiceInjection` for components and models; `ReferenceGraphBuilder.resolveInjection()` (`:338-342`) also returns early for other consumers.
**Reproduced:** `AbstractBaseServlet` (plain abstract class) holding `@Reference TokenService` + `TokenServlet extends AbstractBaseServlet` → `AEM-DEAD-003A` "zero @Reference injections" for `TokenServiceImpl`. This is the most common AEM bundling idiom for shared servlet logic.

### P1-8 · Annotation constants and concatenation are stored as raw source text
`AnnotationUtils.expressionToString()` (`:77-90`) only handles string literals, `ClassExpr` and array initialisers; anything else degrades to `expression.toString()`.
**Reproduced:** `configurationPid = Consts.PID` + `com.acme.consts.pid.cfg.json` → false `AEM-DEAD-001` (the literal-string twin was fine). `property = { "sling.servlet.paths=" + Consts.PATH }` → `AEM-QUAL-001` missed **and** the component also reported as an unused service, because `SystemEntryPointCatalog` never saw the `sling.servlet.*` key.

### P1-9 · `@Component(factory=...)` identity is ignored by ActivePids
`ReferenceGraphIndex.computeActivePids()` (`:122-137`) uses `name` or class FQN only; the factory id parsed by `OsgiComponentVisitor` is never used.
**Reproduced:** `factory="com.acme.plain.factory"` (and no `configurationPid`) + `com.acme.plain.factory~tenant1.cfg.json` → `AEM-DEAD-001`. Controls for `name`, `$`, arrays and `ConfigurationPolicy.IGNORE` all pass.

### P1-10 · `AEM-PERF-001/002` false positives on two common idioms, one false negative
`parser/java/visitor/ResourceLeakVisitor.java:56-70` and `:299-309` — a field initialiser has no enclosing method, so the guard short-circuits; and release is only recognised as a literal `resolver.close()` on the same receiver name.

| Fixture | Expected | Actual |
|---|---|---|
| field resolver closed in `@Deactivate` | clean | **reported** (FP, ERROR severity) |
| `finally { closeQuietly(resolver); }` | clean | **reported** (FP) |
| explicit `close()` in `finally` | clean | clean ✔ |
| never closed | report | report ✔ |
| early `return null` before the close | report | **not reported** (FN) |

## Rules

### P1-11 · `AEM-DEAD-005` (orphaned clientlibs) is the noisiest rule in the set
`rules/impl/AemDead005Rule.java`
* `:53` walks `Files.walk(projectRoot)` and `isClientlibDefinition()` (`:103-107`) has **no** `node_modules`/`target`/`.git` filter — the filter exists only in `isTemplate()` (`:115-118`). On the real project **8 of 22 findings** are junk from `ui.frontend/node_modules/aem-clientlib-generator/test/...`.
* One violation is emitted per **(file × category)** (`:81-99`), and the global dedupe (`DefaultAemCodeAnalyser:389-399`) keys on file+line, so `test.base.apps.six` is reported 8 times.
* Categories are lowercased at `:148` but matched with `content.contains(category)` at `:130` — **case-sensitive**, so any camelCase category can never be found and is always "orphaned".
* Reference detection is a raw substring test over `.html/.jsp/.js/.xml`, so any incidental mention (a comment, the app name in a path) counts as a reference.

**Fix:** filter the walk, aggregate per category, compare case-insensitively with a word-boundary/`data-sly-call`-aware match.

### P1-12 · `AEM-DUP-001` misses duplicates unless index 0 is involved
`AemDup001Rule.java:135-143` compares every ranking to `list.get(0)` only.
**Reproduced:** three servlets on `/bin/dupone` with rankings `100, 0, 0` → **no finding**, although the two `0`-ranked servlets hijack each other. Two servlets (`0, 0`) → 1 finding. The outcome also depends on discovery order, so results can differ between machines.

### P1-13 · `AEM-QUAL-004` and `AEM-QUAL-006` double-report the same field
`AemQual004Rule` (ERROR) and `AemQual006Rule` (WARNING) both fire on `private ResourceResolver cachedResolver;` → two violations on the same line with different severities. `QUAL-006` skips static fields (`:28`), `QUAL-004` does not, so behaviour diverges further. **Fix:** one owner for resolver/session fields.

### P1-14 · `AEM-QUAL-003` matches on the type-name suffix
`AemQual003Rule.java:44-50` uses `type.endsWith("Scheduler")`, so a project class `@Reference ContentScheduler` (no whiteboard scheduler involved) is reported as an AMSCORE-554 violation. **Fix:** exact FQN match on `org.apache.sling.commons.scheduler.{Scheduler,ScheduleOptions}`.

### P1-15 · `AEM-DEAD-004` treats any third-party `data-sly-use` as a broken class
`AemDead004Rule.java:75-85` exempts only `com.adobe.cq.`, `org.apache.sling.`, `com.day.cq.`, `java/javax/jakarta`, and ignores the `DependencyBytecodeIndexer` that `RuleContext` already carries.
**Reproduced:** `data-sly-use.fmt="org.apache.commons.lang3.StringUtils"` → `[ERROR] AEM-DEAD-004 ... references missing Java class`. ERROR severity blocks the gate.

### P1-16 · `AEM-CLOUD-002` (JSP) bypasses module classification
`AemCloud002Rule.java:36-37,59-68` walks the whole checkout; `/it.tests/` is not excluded even though `ModuleClassifier` already classifies that module as `TEST_MODULE` and `mayContainContent()` excludes it everywhere else. **Reproduced:** a JSP under `it.tests/src/main/content/jcr_root/...` produced a WARNING.

### P1-17 · `AEM-DEAD-003A` flags framework-invoked components
`SystemEntryPointCatalog` lists `Servlet`, `Filter`, `EventListener` but not `javax.servlet.ServletContextListener`, `jakarta.*` equivalents, or Sling `Job`. **Reproduced:** a `@Component(service = ServletContextListener.class)` lifecycle listener → "Unused OSGi Service" (typed BUG).

### P1-18 · `AEM-CLOUD-003` allowlist gaps and collapsed findings
* `OOTB_SYSTEM_USERS` (`:94-101`, 21 entries) omits `translation-service`, `content-sync-service`, `cloud-service`, `replication-receiver-service`, `forms-service` → mappings to product users get flagged with no possible remediation.
* `UNRESOLVED_SERVICE_USER` uses `TextRegion.forWholeFile()` and the global dedupe key is `ruleId|file|line|col|subtype`, so **N undeclared users in one mapper config collapse to 1 finding**. **Reproduced:** two unmapped users → one violation.

### P1-19 · `AEM-SEC-002` flags constant redirect targets
`AemSec002Rule.java:38` exempts only `sendRedirect("...")` — an inline literal. `static final String LOGIN_PAGE = "..."; response.sendRedirect(LOGIN_PAGE);` → "Redirect target is not a constant and may be user-controlled", which is factually wrong, on the most common benign form.

### P1-20 · `AEM-PERF-004` is a single-line text scan
`AemPerf004Rule.java:35-41`: `queryCall && hasQueryLiteral && !line.contains("limit")` over raw lines.
**Reproduced in one file:** a commented-out query and a log string mentioning `createQuery` were both reported; the real multi-line `createQuery(` + `"SELECT ..."` was **missed**. Any line containing `limit` (e.g. `maxResultsLimit`) suppresses a true finding.

## Suppression, exporters, CLI

### P1-21 · Inline suppression cannot name SonarJava rules
`baseline/InlineSuppressions.java:98` — `remainder.split("[^A-Za-z0-9_*.-]+")` splits on `:`, so `java:S1166` becomes `["java","S1166"]` and never matches. **12 of the 14 findings** in the reference project are `java:S*`, i.e. the feature is a silent no-op for the majority of findings. Verified end-to-end: inserting the directive did not change the finding count.

### P1-22 · Inline suppression matches too much
* `:76-82` checks 0-based indices `lineNumber-2` **and** `lineNumber-1`, i.e. a directive on the finding's **own** line also suppresses — though the docstring documents only "next-line".
* `disable-file` scans the whole file, so a directive at the bottom suppresses findings above it (may be intended; undocumented).
* Directives are matched inside string literals and prose, so `"... aem-analyser-disable-next-line AEM-DEAD-001 ..."` in a log message suppresses a real finding.

### P1-23 · SARIF output is not ingestible for paths with spaces
`export/SarifReportExporter.java:151-154` emits `artifactLocation.uri` verbatim. SARIF 2.1.0 types `uri` as an RFC 3986 URI-reference, where a raw space is illegal.
**Reproduced:** after renaming a module directory to `core bundle`, 3 of 26 results carried `"uri": "core bundle/src/..."`. GitHub code scanning and Azure DevOps reject or silently skip these.
Also: `originalUriBaseIds` (`:90-91`) defines `%SRCROOT%` **without** a `uri`, so the base cannot resolve; and results carry no `partialFingerprints`, so alert dedupe/`ruleId` tracking in GitHub churns on unrelated edits (the project already computes a stable `Fingerprint`).

### P1-24 · CLI option precedence
`AnalyserCli.java:408-424, 560-580`
* `-f HTML --html custom/deep/r.html` → the custom path is ignored; the file goes to `<output>/aem-analyser-report.html`.
* `--no-html -f HTML` → HTML is still written, contradicting the help text.
* `-f CONSOLE,console` → the report prints twice (no dedupe in `parseFormats()`).

### P1-25 · `--min-coverage 0` fails with no coverage data
`AnalyserCli.java:834-839` treats "not detected" as a gate failure even for a threshold of `0.0`, so a "measure but don't gate" configuration is impossible.

### P1-26 · Maven mojo silently skips in scoped builds
`AbstractAnalyserMojo` resolves the execution root and returns early when the module set is a subset (`-pl`), reporting an empty, green build with no warning.

## Other confirmed P1s

### P1-27 · Coverage engine advertises JaCoCo XML but only reads CSV/`.exec`
`discovery/CoverageDiscoveryEngine` — an XML report path is accepted and never parsed; combined with no freshness check outside `--collect-coverage`, a stale artifact can satisfy `--min-coverage` indefinitely.

### P1-28 · `--timeout` bounds only Java parsing
`DefaultAemCodeAnalyser.parseJavaSourcesParallel()` applies the deadline to the parse futures; discovery, HTL/JCR scanning, the graph build, PMD CPD, the SonarJava scan and coverage discovery are unbounded, so `--timeout 300` can be exceeded by minutes.

---

# P2 — diagnostics, UX, metadata

| # | Item | Where |
|---|---|---|
| P2-1 | Config→component resolution is a linear scan per config (`O(configs × components)`), recomputing `computeActivePids` per pair, while the O(1) `findComponentsByPid` index has **zero callers**. Measured benign (1000×1000 → 2.3 s, parsing dominates) — the defect is duplicated logic, not latency | `ReferenceGraphBuilder.java:305-315`, `ReferenceGraphIndex.java:83-85` |
| P2-2 | `HtlScanner.lineNumber()` rescans the file from offset 0 per match → O(matches × file size) on large templates | `HtlScanner.java:149-157` |
| P2-3 | `AemDead005Rule` performs two full `Files.walk(projectRoot)` passes; on the 524 MB reference project that includes `node_modules` | `AemDead005Rule.java:53,72` |
| P2-4 | `--fix` re-analyses before writing reports (correct), but a failed fix never affects the exit code | `AnalyserCli.run()` |
| P2-5 | `HistoryStore` re-parses the whole `runs.jsonl` each run and grows unbounded | `history/HistoryStore.java:28+` |
| P2-6 | Maven mojos are not annotated `threadSafe`; `mvn -T 4` warns and serialises them (no static mutable state found) | `CheckMojo.java:22-26`, `ReportMojo` |
| P2-7 | Documented exit contract ("0 clean, 1 findings at or above the configured threshold") does not describe the implicit always-on ERROR gate; there is no report-only mode | `docs/index.md`, `AnalyserCli.java:850-864` |
| P2-8 | Metadata drift: `AEM-QUAL-005` mapped to `CWE-798` (hardcoded credentials) though it flags administrative login; `AEM-QUAL-003` typed `SECURITY_HOTSPOT` while tagged quality/aem-cloud | `rules/metadata/RuleRegistry.java:71-99` |
| P2-9 | `AEM-CLOUD-005` reports at most one invalid runmode per file (`break` after the first) | `AemCloud005Rule` |

---

# Suspected — not reproduced

Code-reading only. Treat as leads, not facts.

| # | Hypothesis | Where |
|---|---|---|
| S-1 | `ExportPackageCollector` splits OSGi headers on plain commas, so quoted attributes containing commas, version **ranges** (`version="[1.0,2.0)"`) and trailing `.*` wildcard exports are mis-parsed → wrong exported/unexported decisions | `discovery/ExportPackageCollector.java` |
| S-2 | `ProjectDiscoveryEngine` has no hard exclusion for `node_modules`/`target`/`.git` when hunting poms, resolves profiles without full reactor property inheritance, and follows symlinks without cycle detection | `discovery/ProjectDiscoveryEngine.java` |
| S-3 | `AEM-DEAD-002B` sees only `CONSUMES_PROPERTY` edges, so values read via `componentContext.getProperties().get("x")` or a `Dictionary` projection count as unconsumed | `AemDead002BRule`, `ComponentPropertyConsumptionVisitor` |
| S-4 | `AEM-DEAD-003B` fails open: when no CQ component was discovered (`componentResourceTypes.isEmpty()`), **every** model is exempt, so a bundle-only run can never report a model | `AemDead003BRule.java:94-99` |
| S-5 | `AEM-QUAL-001` matches `lower.startsWith("sling.servlet.paths")`, so a property named `sling.servlet.pathsDisabled` also matches; reports per property, not per servlet | `AemQual001Rule.java:34` |
| S-6 | `AEM-CLOUD-001` treats an all-numeric secret as benign and matches on key substrings, so numeric passwords pass and non-secret keys can be flagged | `AemCloud001Rule` |
| S-7 | `JavaSourceParser:74` drops a file entirely when `!result.isSuccessful()`, even when JavaParser recovered a usable AST — one bad line removes every symbol in the file | `JavaSourceParser.java:74` |
| S-8 | SonarJava bridge classpaths only `<module>/target/classes` (no dependency JARs) and swallows `Exception`/`Throwable` from file creation and `astScanner.scan`, so an under-populated or failed scan looks clean | `sonar/SonarJavaScanner.java:66-75,95-105` |
| S-9 | `AEM-CLOUD-003` emits a different severity than its `RuleId` for one subtype, so gating depends on which branch fires | `AemCloud003Rule` |

---

# Refuted — checked and correct

Recorded so nobody re-investigates them. Each was a plausible hypothesis that the code or a run disproved.

| Hypothesis | Verdict |
|---|---|
| `ci.yml` runs `mvn -Prun-its verify` but no such profile exists | **False** — the profile lives in `aem-analyser-maven-plugin/pom.xml:69` with `src/it/basic`. Only the root pom lacks it. CI is fine. |
| `ReferenceGraphIndex.registerOcd` NPEs when an OCD has no pid | **False** — `MetatypeOcdVisitor:47` always supplies a non-null pid (falls back to FQN). |
| 23 config files on disk but 22 config nodes — one silently dropped | **False** — the 23rd file is `filter.xml`. |
| GitHub/GitLab/Azure annotations do not escape `%`, `\r`, `\n` | **False** — escaping is correct. |
| HTML exporter fails to escape `<script>` in paths/messages | **False** — escaping is correct. |
| `parseStrict` silently swallows parse errors | **False** — `JAVA_PARSE_ERROR` diagnostics are raised and surfaced (residual risk S-7). |
| JavaParser needs a symbol solver for Java 21 constructs | **False** — records, switch expressions, method refs, lambdas and text blocks all parse; 18/18 components and 1/1 model found on the real project. |
| `ADAPTS_TO`'s "known JavaClassNode source" restriction loses edges from components/models | **False** — the lookup falls back to the full `classLookup`; source-side recording is fine. |
| `--collect-coverage` breaks when `mvn`/`JAVA_HOME` is missing | **False** — handled gracefully. |
| Unknown rule ids break the SARIF/Sonar exporters | **False** — `RuleRegistry.unknown()` handles them. |
| Baseline is unstable under line moves | **False** — verified stable (+20 blank lines, unchanged suppression). Instability comes from renames and multiplicities (P0-7). |
| Dry-run and real `--fix` diverge | **False** — write parity is correct; the defects are in what gets written (P0-6). |
| `--threads`/`--no-sonar` behave incorrectly | **False** for `--threads`; `--no-sonar` is correct, but see P0-1: `--sonar` is not. |

---

# Recommended order of work

1. **P0-2** (plugin writes `target`) — one line, breaks every consumer's build today.
2. **P0-1** (`--sonar` inversion) — one line, restores a whole rule family.
3. **P0-3 / P0-4** (git-diff false greens) — the CI trust contract.
4. **P0-5** (three rules blind to components) — largest correctness gain per line of change.
5. **P0-6** (`--fix`) — either fix the four defects or mark `--fix` experimental; silent source corruption is worse than no fixer.
6. **P0-7** (baseline multiplicity) — unblocks the baseline feature from being a blanket amnesty.
7. **P0-8** (wildcard imports) — fixes a whole class of phantom "unused service" findings.
8. P1-1/P1-3/P1-5 (config discovery, platform factory PIDs, runmode merging) — the config layer's credibility.
9. P1-11/P1-12/P1-13 (rule noise) — reduces false positives a user sees on day one.

A single regression test per P0 would have caught all eight; the suite has 212 tests and none of them assert on flag semantics, plugin output paths, git failure handling, or component coverage of the file-scanning rules.

---

# Documentation debt (not deleted — awaiting your go-ahead)

`git ls-files '*.md'` → **81 tracked markdown files**. Candidates for removal or merge, with the exact commands, once you approve:

| Item | Evidence | Suggested action |
|---|---|---|
| `plans/` (42 tracked files: `p0/`, `p1/`, `p2/`, `validation/`) | Every P0/P1 item in `plans/backlog.md` is already implemented and recorded as fixed in `CHANGELOG.md` (verified in code: ignored-config paths, runmode index key, hyphenated PIDs, DEAD-002A multi-component, DUP-010 duplicate producer) | `git rm -r plans/` — the changelog is the durable record |
| `PROJECT.md` (333 lines) vs `docs/AEM_CODE_ANALYSER_SPECIFICATION.md` (1,374 lines) | Both describe the same architecture; the spec still carries `Author: Worker 1 (Specification & Plan Author)` front-matter | Keep one. `PROJECT.md` is the cleaner narrative; the spec duplicates §2/§3/§4 |
| `.agents/teamwork/**` | Multi-agent handoff artefacts | **Already untracked** (not in `git ls-files`) — no action |
| `docs/index.md` (384 lines) | Still documents the exit-code contract that P2-7 contradicts | Update, don't delete |

---

# Appendix — reproduce any finding

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21    # a JDK is NOT on the default PATH
export PATH=$JAVA_HOME/bin:$PATH
cd /Users/rvenat01/Documents/rohith/aem-code-analyser
mvn -B -ntp -o test                              # 212 tests, green

JAR=aem-analyser-cli/target/aem-analyser-cli.jar
P=/Users/rvenat01/Documents/rohith/rohith-aem-portfolio

# P0-1: --sonar drops every java:S finding
java -jar $JAR -p $P --no-html | grep -c 'java:S'          # 6
java -jar $JAR -p $P --no-html --sonar | grep -c 'java:S'  # 0

# P1-11: node_modules clientlib false positives
java -jar $JAR -p $P --no-html -f SARIF -o /tmp/r && grep -c node_modules /tmp/r/aem-analyser.sarif  # 8

# P0-3 / P0-4: vacuous green builds
java -jar $JAR -p $P --no-html --since does-not-exist ; echo "exit=$?"   # Total: 0, exit 0
java -jar $JAR -p $P --no-html --changed-only      ; echo "exit=$?"      # Total: 0, exit 0

# P0-2: Maven plugin writes a file named target
mkdir -p /tmp/mp && cd /tmp/mp   # minimal pom + src/main/java/demo/App.java
mvn -o -B io.github.rohithv16:aem-analyser-maven-plugin:1.0.0-SNAPSHOT:check
file target        # target: ASCII text
mvn -o -B compile  # BUILD FAILURE: could not create parent directories
```
