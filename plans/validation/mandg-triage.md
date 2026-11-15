# Enterprise project AEMaaCS triage — analyser validation

Command: `java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/enterprise-aem-project --format SARIF,CONSOLE --output <tmp> --no-color --no-sonar`

Result (baseline before fixes): **95 findings (29 ERROR, 48 WARNING, 18 INFO)**, 8.4 s.
Metrics: 9 modules, 71 Sling Models, 93 cq components, 66 components, 120 OSGi configs.

> Note: the 8 `AEM-PERF-002` hits were produced by a jar built before the resolver-ownership fix; the
> current build reports 0. A determinism run is still recommended (see follow-ups).

## Verdict table

| rule | total | confirmed FP | TP | policy |
|---|---:|---:|---:|---:|
| AEM-DUP-010 | 45 | 8 | 37 | 0 |
| AEM-DEAD-003B | 18 | 17 | 1 | 0 |
| AEM-DEAD-001 | 11 | 0 | 11 | 0 |
| AEM-SEC-001 | 9 | 0 | 7 | 2 |
| AEM-PERF-002 | 8 | 8 | 0 | 0 |
| AEM-DEAD-003A | 2 | 0 | 2 | 0 |
| AEM-DEAD-002B | 1 | 0 | 1 | 0 |
| AEM-DEAD-004 | 1 | 1 | 0 | 0 |
| **total** | **95** | **34** | 59 | 2 |

False-positive rate 36 %. Nine blocking errors were false.

## Confirmed false positives

### AEM-PERF-002 (8/8 FP)

All hits were `resourceResolver.adaptTo(Session.class)` where the resolver was request-owned, a method
parameter, or injected/resolver-managed. Fixed generically by treating adapted sessions as borrowed views
and checking the receiver's provenance (TWR, parameter, field, request/asset resolver).

### AEM-DEAD-003B (17/18 FP)

Models consumed through Sling Models injection (`@Self`, `@ChildResource List<T>`, `@Named`) or as a
superclass/return type of another model were reported unused. Nested config interfaces were also flagged.
Generic fix required: build a use graph from model field/parameter/return/superclass type references.

### AEM-DUP-010 (8 FP)

- same-file overlapping windows (one-line shift compared with itself)
- constructor-delegation-only blocks (`this(...)`) and delegating accessors (`return x.getY();`)
- Javadoc lines inflating the reported line count

### AEM-DEAD-004 (1 FP)

`ui.apps/.../errorhandler/ResponseStatus.java` lives under `jcr_root` (content modules set
`sourceDirectory=jcr_root`), so it is compiled but never indexed by the analyser.

## True positives worth acting on

- `AEM-DEAD-001` ×11: four PIDs with no effective binding. `EgrSendEmailNotification` configs use a stale
  package (`services.impl` vs `workflows.egr`); the other seven are OCD-named configs whose components
  never declare `configurationPid`.
- `AEM-SEC-001` ×7: real `context='unsafe'` in script/attribute/JSON-LD contexts.
- `AEM-DEAD-003A` ×2: `TagsExtractor`, `PublicationStatusChecker` have no production consumer.
- `AEM-DEAD-002B` ×1: `AssetUrlTransformerFactory.Config.enabled()` is never read.

## Generic fixes (no project-specific heuristics)

1. Build the Sling Models use graph from injection (`@Self`, `@ChildResource`, `@Named`), superclass,
   interfaces and return types. Target: DEAD-003B 18 → 1.
2. Resolver/session provenance: only factory-owned resolvers can leak; adapted sessions are borrowed.
   Target: PERF-002 8 → 0.
3. Reject same-file overlapping duplicate windows and delegation-only blocks; exclude comments from
   reported line counts; include both file paths in the message.
4. Index `.java` outside `src/main/java` (content modules with `sourceDirectory=jcr_root`).
   Target: DEAD-004 1 → 0.
5. Aggregate AEM-DEAD-001 per PID: list all designating components and the runmode folders affected.
6. Determinism: repeated runs on the same tree must report the same set.

## Wording to generalise

- PERF-002 remediation must not advise `logout()` on an adapted session.
- DUP-010 must print `path:line` for both sides and exclude comment lines from counts.
- DEAD-003B should state which channels were checked.
- SEC-001 should suggest context-specific fixes (`uri`, `scriptString`, JSON escaping) and a JSON-LD tier.
- No project-specific names were found in any emitted message.

## Post-fix re-validation

After the generic fixes above:

| rule | before | after |
|---|---:|---:|
| AEM-DUP-010 | 45 | 43 |
| AEM-DEAD-003B | 18 | 1 |
| AEM-DEAD-001 | 11 | 11 (true positives) |
| AEM-SEC-001 | 9 | 9 |
| AEM-PERF-002 | 8 | 0 |
| AEM-DEAD-003A | 2 | 2 (true positives) |
| AEM-DEAD-002B | 1 | 1 (true positive) |
| AEM-DEAD-004 | 1 | 0 |
| **total** | **95** | **67** |

Two consecutive runs produced identical counts (67 each), so the earlier PERF-002 flip was caused by
running against a jar that was being rebuilt during triage, not by analyser non-determinism.

Remaining 43 AEM-DUP-010 findings are mostly genuine copy-paste in the shared servlets; the
detector now rejects same-file overlapping windows and delegation-only blocks.
