# C10 — Platform PID prefix matched across segment boundaries

Status: Done (wave 1, 2026-11-15)
Priority: P0
Area: aem-analyser-core / `rules.catalogs.PlatformPidCatalog`

Verified evidence: `isPlatformPid` used `pid::startsWith` against entries such as `com.adobe.aem`
(no trailing dot), so `com.adobe.aemfoo.MyService` was treated as a platform PID and silently exempt
from `AEM-DEAD-001`.

Problem: a real customer PID could be suppressed by an unrelated package prefix.

Desired behavior: wildcard prefixes match only on a segment boundary; package prefixes (already ending
in `.`) keep matching descendants; class-level prefixes also match their exact PID.

Files to change (landed):
- `aem-analyser-core/.../rules/catalogs/PlatformPidCatalog.java` — `matchesPrefix` helper.

Implementation steps:
1. `prefix.endsWith(".")` → `pid.startsWith(prefix)`.
2. Otherwise `pid.equals(prefix) || pid.startsWith(prefix + ".")`.

Tests to add (landed):
- `rules/catalogs/PlatformPidCatalogTest` — `com.adobe.aem.sample.*` and `com.adobe.aem` match;
  `com.adobe.aemfoo.*` does not; package prefixes still match descendants.

Acceptance criteria: no cross-segment suppression; existing platform fixtures unchanged.

Risks / notes: some catalogs may need new entries once a real FP is reproduced; never add speculative
prefixes.
