# E1 — AEM-DEAD-001 PID semantics hardening

Status: Verified
Priority: P1
Area: aem-analyser-core / `rules.impl.AemDead001Rule`, `graph.ReferenceGraphIndex`,
`rules.catalogs.PlatformPidCatalog`, `discovery.OsgiConfigScanner`

Problem / opportunity:
Already handled (verified — do not re-implement):
- `ReferenceGraphIndex.computeActivePids` (l.112-127) returns an empty active-PID set for
  `ConfigurationPolicy.IGNORE`, expands `$` to the component name (falling back to the class FQN),
  honours an explicit `configurationPid` list, and de-duplicates.
- `AemDead001Rule.findComponentNamed` (l.129-137) already matches on both
  `getComponentClassFqn()` and `@Component(name=…)`.
- OCD-named configs: `ReferenceGraphIndex.registerOcd` (l.47-51) indexes an OCD under both its FQN
  and its `pid`, and `AemDead001Rule` uses `context.index().findOcd(pid)`.
- Factory configs: `ConfigFilePidResolver` strips `~subname` and legacy `-subname`, so the base PID
  is what reaches the rule.
Real gaps:
1. External-PID suppression is inert. `RuleContext.isExternalPid` reads
   `dependencyIndex.getComponentPids()`, but nothing ever populates
   `AnalysisContext.dependencyJarRoots` (see E14), so it is always empty.
2. `PlatformPidCatalog.WILDCARD_PREFIXES` contains `"com.adobe.aem"` with no trailing dot, so
   `isPlatformPid("com.adobe.aemfoo.my.Pid")` returns true — a real app PID is silently suppressed.
3. Legacy `.config` hyphen splitting (C8) can produce a wrong base PID; `ambiguousHyphen` is never
   consumed, so a dotted factory suffix yields a false `ORPHANED_CONFIGURATION`.
4. `.content.xml` PIDs carry a bogus `.content` suffix: `OsgiConfigScanner.stripExtension` strips
   `.content.xml` correctly (l.132-140) but `OsgiConfigParser.stripExtension` (l.52-56) strips only
   the last dotted segment, so `com.example.Foo.content.xml` is parsed with PID
   `com.example.Foo.content` and never matches its component.
5. Runmodes are not part of the predicate: a config under `config.publish` is judged against all
   components regardless of runmode. Out of Phase-1 scope — document it rather than guess.

Desired behavior:
- A PID is platform/external only on segment-boundary matches.
- `.content.xml` resolves to the same PID as the scanner reports.
- Ambiguous legacy factory PIDs are disambiguated before the orphan check (with a diagnostic).
- External PID suppression actually works when JAR roots are supplied.

Files to change:
- `core/rules/catalogs/PlatformPidCatalog.java` — normalise prefixes to end with `.`, add
  `isPlatformPid` segment check; keep `matchesPattern` for user patterns.
- `core/parser/config/OsgiConfigParser.java` — reuse `OsgiConfigScanner`-style strip (shared helper,
  e.g. `ConfigFilePidResolver.stripExtension(fileName)`).
- `core/graph/ReferenceGraphBuilder.java` — before building `CONFIGURES`, if
  `definition.factoryAmbiguous()` then re-resolve against known component active PIDs.
- `core/api/DefaultAemCodeAnalyser.java` — pass `context.ignoredConfigPaths()` into the config loop
  (shared with C1) so E1 fixtures can exclude known-noise files.

Implementation steps:
1. Extract `ConfigFilePidResolver.stripExtension(String)` and use it from both scanner and parser;
   add a unit test for `.cfg.json`, `.config`, `.cfg`, `.content.xml`.
2. Make `PlatformPidCatalog` boundary-safe (`prefix.endsWith(".")` normalisation in a static block);
   keep the public API identical.
3. Disambiguation: for `factoryAmbiguous` configs, try `basePid + "-" + firstToken(suffix)` and the
   full base PID against `ReferenceGraphIndex.findComponentsByPid`; pick exactly one match, else
   keep the current split and record a diagnostic (E11).
4. Fix the `com.adobe.aem` prefix and add the missing obvious entries found during validation
   (only with evidence from a real project, never speculatively).

Tests to add:
- `PlatformPidCatalogTest`: `com.adobe.aem` vs `com.adobe.aemx.Foo`; exact-PID case sensitivity.
- `ConfigFilePidResolverTest`: `.content.xml` base name equals scanner base name.
- `AemDead001RuleTest` fixtures: `configurationPid="a.b.C"` + `a.b.C.cfg.json` (no violation);
  `$` form `configurationPid="com.example.$"` + `com.example.MyComponent.cfg.json`;
  `configurationPolicy=IGNORE` (subtype `IGNORED_CONFIGURATION_POLICY`);
  factory `x.y.Pid~one.cfg.json`; legacy `x.y.Pid-one.config`;
  external PID with a `dependencyJarRoots` stub JAR.

Acceptance criteria:
- The four fixtures above emit exactly the expected subtype (or none) and no other violation.
- `AEM-DEAD-001` never reports a PID that `PlatformPidCatalog` treats as platform under E1 rules.
- Existing `OsgiConfigScannerTest` and `EndToEndAnalysisTest` stay green.

Risks / notes:
- Runmode-aware analysis (gap 5) would change many results; keep it a documented limitation until
  F2 supplies per-runmode configuration.
- Do not hardcode more platform PIDs without a false-positive reproduction; the catalog is already
  the largest FP-suppression surface in the project.
