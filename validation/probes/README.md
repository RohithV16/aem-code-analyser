# Rule probes — deliberate per-rule validation

`validation/probes` is a self-contained **source fixture set** laid out like an AEM reactor
(`core`, `ui.apps`, `ui.config`) that deliberately contains **one positive case for every rule**.
It exists purely as analyser input — the analyser reads the sources, it does not build them, and
the fixture modules intentionally declare no dependencies (so `mvn package` inside this directory
is not expected to work). It exercises the full CLI pipeline (discovery → parsing → graph → rules
→ SARIF) exactly as a customer repo would.

## Run directly

```bash
mvn -q -DskipTests package
scripts/run-rule-probes.sh
```

The script runs the analyser against `validation/probes`, then verifies that the SARIF report
contains every rule listed in `expected-rules.txt` **and that each rule fired on a probe file**
rather than incidentally on unrelated code. Missing rules, rules demonstrated only by non-probe
code, rule ids absent from `expected-rules.txt`, and files skipped by the parser all exit non-zero.

## Inject into a real repo (safe)

```bash
# Copy the target to a temp dir, add the probe module to the root POM, run, verify, delete.
scripts/inject-rule-probes.sh /path/to/aem-project

# Keep the temp copy for inspection
KEEP=1 scripts/inject-rule-probes.sh /path/to/aem-project
```

The original repository is never modified: the script `rsync`s it to a temporary directory, copies
`analyser-probes/` into the copy, and appends `<module>analyser-probes</module>` to the root POM.
This tests the probes in the target’s real module layout and Maven profile context.

## Probe matrix

| Rule | Deliberate probe |
|---|---|
| AEM-COMP-001 | `components/classic/dialog.xml` (Classic UI dialog) |
| AEM-COMP-002 | `components/zzprobe/broken-resource.html` renders `probe/components/doesnotexist` |
| AEM-DEAD-001 | `config/com.probe.missing.Service.cfg.json` (no component) |
| AEM-DEAD-002A | `ProbeConfigComponent` config has `ghost` not declared in `ProbeOcd` |
| AEM-DEAD-002B | `ProbeOcd.unusedProp()` is never consumed |
| AEM-DEAD-003A | `DeadServiceImpl` exposes an interface nobody injects |
| AEM-DEAD-003B | `UnusedProbeModel` has no resourceType/adaptation/usage |
| AEM-DEAD-003C | `DelayedComponent` is `immediate=false` with no service |
| AEM-DEAD-004 | HTL `data-sly-use.missing="com.probe.DoesNotExist"` |
| AEM-DEAD-005 | clientlib category `probe.orphan` is never referenced |
| AEM-DEAD-007 | `components/zzprobe/inc/broken.html` includes a missing template |
| AEM-DEAD-006 | `components/zzprobe/i18n-broken.html` uses `probe.missing.key`, absent from `i18n/en.json` |
| AEM-DUP-001 | two servlets register `/bin/probe` |
| AEM-DUP-002 | `com.probe.DupPid` in both `.cfg.json` and `.config` |
| AEM-DUP-003 | two models claim `resourceType="probe/dup"` with the same adapter |
| AEM-DUP-010 | identical `compute(int)` methods in two classes |
| AEM-QUAL-001 | path-bound servlet (`sling.servlet.paths`) |
| AEM-QUAL-002 | `BadProvider implements ResourceResolver` |
| AEM-QUAL-003 | component injects `Scheduler` |
| AEM-QUAL-004 | component field of type `PageManager` |
| AEM-QUAL-005 | `getAdministrativeResourceResolver(null)` |
| AEM-QUAL-006 | component field of type `ResourceResolver` |
| AEM-QUAL-008 | `UnprovidedReferenceProbe` references a project interface no component registers |
| AEM-QUAL-007 | `RequestInjectionProbe` is `adaptables = Resource.class` but injects a `SlingHttpServletRequest` |
| AEM-PERF-001 | factory resolver acquired and never closed |
| AEM-PERF-002 | `repository.login(...)` session never closed |
| AEM-PERF-003 | `select * from nt:base` without `where` |
| AEM-PERF-004 | `select * from [cq:Page]` without a limit |
| AEM-CLOUD-001 | `apiKey` plaintext in a config |
| AEM-CLOUD-002 | `probe.jsp` in `ui.apps` |
| AEM-CLOUD-003 | `getServiceResourceResolver` with an unmapped subservice |
| AEM-CLOUD-004 | deprecated `org.apache.commons.lang.StringUtils` import |
| AEM-CLOUD-005 | config under `config.foo` |
| AEM-CLOUD-006 | dispatcher vhost includes a missing file, duplicates `ServerName`, omits `DocumentRoot`; legacy `src/dispatcher.any` |
| AEM-CLOUD-007 | `ui.content` and `content.extra` both filter `/content/probe`; `ui.content` uses `mode="replace"` |
| AEM-SEC-001 | HTL `context='unsafe'` |
| AEM-SEC-002 | `sendRedirect(request.getParameter("next"))` |
| AEM-A11Y-001 | `components/zzprobe/a11y-broken.html` has an `<img>` with no alt |
| AEM-A11Y-002 | the same template has an icon-only anchor with no href |

## Adding a probe

1. Add the smallest source/config that honestly triggers the rule.
2. Add the rule id to `expected-rules.txt`.
3. Run `scripts/run-rule-probes.sh`; the probe must fire **and** no unexpected rule should be
   introduced unless it is also documented in the matrix.
4. Re-run `scripts/inject-rule-probes.sh` on at least one real repo before merging.
