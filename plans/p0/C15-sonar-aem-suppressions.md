# C15 — AEM-aware SonarJava false-positive suppressions

Status: Done (wave 1, 2026-11-15)
Priority: P0 (gate adoptability)
Area: aem-analyser-core / `sonar.SonarAemSuppressions`, `api.DefaultAemCodeAnalyser`

Verified evidence from the real-project baseline: three SonarJava rules fired on correct AEM code:
- `java:S100` on `webconsole_configurationFactory_nameHint()` — a reserved OSGi Metatype method name.
- `java:S1186` on empty `Filter.init(FilterConfig)` / `destroy()` — required servlet lifecycle no-ops.
- `java:S1192` on `addNode("cursor", "nt:unstructured")` — a JCR node name/type, not a constant candidate.

Problem: four false positives (two of them the only remaining style noise) made the gate look noisy
and trained users to ignore Sonar findings.

Desired behavior: suppress only the specific AEM framework idiom, keep the rule active everywhere
else.

Files to change (landed):
- `aem-analyser-core/.../sonar/SonarAemSuppressions.java` (new).
- `DefaultAemCodeAnalyser` — filters Sonar findings through the suppression layer.

Implementation steps:
1. `S100`: suppress only the exact reserved method name.
2. `S1186`: suppress empty `init`/`destroy` only in a class that implements/extends a servlet `Filter`.
3. `S1192`: suppress literals used with JCR node APIs (`addNode`/`getNode`/`hasNode`) or known node
   type prefixes (`nt:`, `cq:`, `rep:`, `sling:`).
4. Never suppress a rule globally; unmatched findings pass through.

Tests to add (landed):
- `sonar/SonarAemSuppressionsTest` — one positive and one negative per rule.

Acceptance criteria: portfolio baseline loses the four false positives and keeps `S1166`, `S2208`,
`S1155` and the genuine `S1192` constant finding.

Risks / notes: line-based checks are intentionally simple; a future AST-aware suppression layer can
replace them behind the same API.
