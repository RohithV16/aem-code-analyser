# Rule-probe validation — three independent runs

Date: 2026-11-15
Base commit: `387dad2` (validation ran against the uncommitted working tree on top of it)
Analyser: `1.0.0-SNAPSHOT`, JDK 21

## What was validated

`validation/probes` contains one deliberate positive case per rule, laid out as an AEM reactor
(`core`, `ui.apps`, `ui.config`) but used purely as analyser input. Three independent reviewers
re-ran the harness without editing source or fixtures:

| Run | Target | Mode | Result |
|---|---|---|---|
| 1 | `validation/probes` | direct scan | **PASS** — 30/30, exit 0 |
| 2 | `merkle/aem-engineering-hub` | probes injected into an rsync temp copy | **PASS** — 30/30, exit 0 |
| 3 | `mandg/aemaacs-life` (4.6 GB monorepo) | probes injected into an rsync temp copy | **PASS** — 30/30, exit 0 |

Common checks: every expected rule present; findings resolve to files inside the analysed root;
no missing messages or locations; `ruleIndex` ↔ `ruleId` consistent; no crash, OOM or unhandled
exception; original repositories byte-identical by `git status --short` before and after.

Run 3 measured 86 s end-to-end including the 4.6 GB copy (178 findings, 95 files) with a 2.0 GB peak
RSS. Run 2 completed with 54 findings, 42 of them on probe files. All three runs were repeated and
produced identical pass/fail outcomes.

## Defects and gaps the runs exposed — all fixed

1. **`AEM-COMP-001` had no probe.** The catalogue held 30 rules but the probe matrix only covered 29,
   so the README's "one positive case for every rule" was false. Added
   `components/classic/dialog.xml` + `.content.xml` and extended `expected-rules.txt` to 30.
2. **`AEM-DUP-002` was non-deterministic.** The duplicate group came from a `HashMap` and the
   primary/related pair flipped between runs with identical finding counts. Groups now use a
   `TreeMap` and members are sorted by relative path, so the "first definition" is stable.
   Verified: two consecutive SARIF runs are byte-identical across 43 findings.
3. **Java 21 record patterns silently skipped a file.** `DWPNotificationFormatter.java` failed with
   `VarType cannot be cast to ReferenceType` under JavaParser 3.26.1, producing one
   `JAVA_PARSE_ERROR` and losing that file's symbols. Upgraded to JavaParser 3.28.2; the file now
   parses at `JAVA_21` and a regression test covers `case Ok(var value)`.
4. **The checker was weaker than its documentation.** It only asserted rule presence, read
   `locations[0]`, and never flagged extra rule ids. It now also requires (a) at least one finding
   per rule on a probe path, (b) no reported rule outside `expected-rules.txt`, (c) no catalogue
   entry without a probe, and (d) no result without a location. It exits 2 on an unreadable SARIF
   instead of throwing a traceback.
5. **Analyser failures were masked.** Both harness scripts used `|| true`. They now accept exit 0
   (clean) and 1 (findings, expected) but fail hard on 2, printing the analyser log. The injection
   script also grew a `trap` so a failed assertion no longer leaks a multi-GB temp copy.
6. **Overstated documentation.** The probes are source fixtures, not a buildable reactor (no module
   dependencies, intentionally incomplete stubs). README and probe README now say so.
7. **Every SARIF `relatedLocation` was double-wrapped.** Entries were emitted as
   `relatedLocations[].physicalLocation.physicalLocation.{artifactLocation,region}`, which breaks
   rendering in code-scanning UIs. The exporter now shares a `physicalLocation()` helper without
   re-wrapping; a schema test asserts the shape.
8. **SARIF output was not byte-deterministic.** Findings were identical, but `ruleIndex` values and
   the driver rule order changed on every JVM because `RuleRegistry` held a `Map.copyOf(...)`
   (iteration order is salted per JVM) and multi-key `Map.of(...)` objects were serialized in
   arbitrary order. The catalogue now preserves declaration order and exporter objects use
   `LinkedHashMap`. Three separate JVM runs produce identical SHA-256 hashes.
9. **Accidental probe redundancy in `AEM-DUP-010`.** The two servlet stubs had byte-identical
   bodies, so the rule had a second, unintended fixture. `ProbeServletTwo` now differs, leaving a
   single finding on the intended `compute()` pair.
10. **The checker could not see parse regressions.** Diagnostics are only printed under
    `--verbose`, which the harness never passes. The checker now reads
    `runs[0].invocations.toolExecutionNotifications` and fails the fixture run on any
    `*_PARSE_ERROR` (injection runs pass `--allow-parse-errors`, since a real repo may contain
    genuinely unparseable files).

## Remaining known limitations

- Probe fixtures are intentionally coarse: `ProbeComponents.java` hosts ~20 findings across nine
  rules, and `AEM-DEAD-001` is satisfied by several incidental orphan configs. Deleting a single
  fixture may not fail the suite, so edits there should be reviewed by eye.
- A rule that cannot be triggered by any static fixture would need a dedicated fixture module rather
  than another member of the mega-fixture.

## Reproduce

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn -q -DskipTests package
scripts/run-rule-probes.sh                      # 30/30
scripts/inject-rule-probes.sh /path/to/aem-repo # 30/30, original untouched
```

After the fixes above, both real-repo injections were re-run on the final jar: Merkle and Mandg each
reported 30/30, left their repositories unchanged, and leaked no temp copies. Three separate JVM
runs against `validation/probes` produced the same SARIF SHA-256
(`001d63fc52b47fe5e8703a9a6dcef01cb1a32ec835740beab4c75ae23f7c4c28`).

`mvn clean verify` on the same tree: **212 tests, 0 failures**.
