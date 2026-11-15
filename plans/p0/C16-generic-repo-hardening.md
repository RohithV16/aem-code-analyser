# C16 — Generic-repo hardening (no sample-project assumptions)

Status: Done (2026-11-15)
Priority: P0
Area: aem-analyser-core (rules, parsers, graph), exports

## Problem

Cross-repo validation (Enterprise project AEMaaCS and the portfolio) exposed heuristics that only worked for the
sample projects the rules were developed against:

- unresolved Java symbols were treated as literal values (subservice constants/map variables)
- sessions adapted from borrowed resolvers were reported as leaks
- Sling Models consumed through injection were reported unused
- Java in content packages (`jcr_root`) was invisible to HTL reference checks
- duplicate detection reported same-file shifted windows and delegation boilerplate
- messages contained internal implementation names and build-oriented wording

## Fixes (landed)

| Area | Change |
|---|---|
| `AEM-CLOUD-003` | `SubserviceCall.Resolution` distinguishes `LITERAL` / `NULL` / `UNRESOLVED`; unresolved variables and constants fail open. Null-subservice reporting also fails open when mapper config exists. |
| `AEM-PERF-002` | Resolver provenance: TWR resources, parameters, injected fields and request/asset resolvers are borrowed; only factory-owned resolvers can leak. |
| `AEM-DEAD-003B` | Models are used when referenced by another model's field/parameter/return type, superclass or interfaces; nested models are skipped. |
| `AEM-DEAD-004` | `src/main/content/jcr_root` is scanned for `.java` (content-package error handlers). |
| `AEM-DUP-010` | Same-file overlapping windows and delegation-only blocks are rejected; message includes both `path:line` ranges. |
| `AEM-CLOUD-001` | `config.local` is WARNING (not deployed by Cloud Manager); no invented secret name in remediation. |
| `AEM-SEC-001` | `data-sly-element` unsafe contexts are WARNING. |
| Messages | `Target:` instead of `Target PID:`, `GATE FAILED` instead of `BUILD FAILURE`, no Sonar check class names, generic remediations. |

## Validation

- Enterprise project: 95 → **67** findings; `DEAD-003B` 18 → 1, `PERF-002` 8 → 0, `DEAD-004` 1 → 0.
- Portfolio: 13 findings, 2 ERROR, 0 duplicates (unchanged except CLOUD-001 severity).
- Two consecutive Enterprise project runs produced identical counts.
- `mvn clean verify`: 181 tests, 0 failures.

## Follow-ups

- Per-bundle service-user mapping resolution (needs Bundle-SymbolicName linkage).
- Duplicate detector: comment-aware line counts and normalized token-sequence verification.
- Shared-OCD `AEM-DEAD-001` remediation should list every designating component and runmode folder.
- Group repeated `AEM-DEAD-001` findings per PID.

## Additional genericisation (API-shape independence)

- **Service-resolver acquisitions**: no longer keyed on `getServiceResourceResolver` alone. The
  extractor recognises `getResourceResolver(Map)`, `getServiceResourceResolver(Map)`,
  `loginService`/`loginAdministrative`, and decides ownership from receiver type
  (`ResourceResolverFactory`/`SlingRepository`), not from the variable name.
- **Auth-map construction**: `Map.of`, `Collections.singletonMap`, `Map.ofEntries(Map.entry(...))`,
  `immutableMap.of`, `put(...)` and nested wrappers are all scanned for the SUBSERVICE entry.
- **Administrative access**: `loginAdministrative`/`getAdministrativeResourceResolver` plus any
  `login`/resolver call whose credentials/auth map uses `admin`/`anonymous`.
- **JCR session ownership**: `login`, `loginByToken`, `loginService`, `loginAdministrative`,
  `impersonate` are recognised; a plain `login` on a non-repository receiver is ignored.
- **Adapted sessions**: `Session.class` and fully-qualified `javax.jcr.Session.class` are both
  recognised.
- **Scheduler usage**: `AEM-QUAL-003` detects `scheduler_expression`/`scheduler_period` OCD-style
  properties and `Scheduler`/`ScheduleOptions` fields, not only dotted component properties.
- **Messages**: "service-resolver acquisition" instead of a single method name; QUAL-005 remediation
  describes the service-user contract generically.

## HTML duplication details

- `CodeMetrics` now carries structured `DuplicateBlock` records (duplicate and original file,
  start/end lines, line count, token count).
- The HTML duplication tab renders each block with a kind classification
  (`Control-flow logic`, `Method block`, `Statement block`, `Accessor boilerplate`,
  `Constructor delegation`), both locations, line/token counts, and a collapsible preview of the
  duplicated source lines.
- Verified on the enterprise target: 43 blocks rendered, 24 control-flow, 17 statement,
  1 method block, 1 constructor delegation.
