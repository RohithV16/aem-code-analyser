# Validation harness — real-project baselines

These docs record how the analyser behaves against a **real AEM project**, not fixtures. They exist so
every rule wave can be checked for regressions *and* for new false positives before release.

Validation target (read-only reference project, lives outside this repo):
`/path/to/archetype-aem-project`

- Baseline report: [`portfolio-baseline.md`](./portfolio-baseline.md)
- Baselines are snapshots, not tests: they must be *re-read*, not blindly diffed, because the accept
  list below encodes human judgement (which findings are true/false positives).

## Rules of engagement

1. **Never modify the target repo.** No `git checkout/reset/clean`, no new files, no formatting.
   It routinely has uncommitted user changes. If you need a clean tree, copy it elsewhere.
2. **All output goes outside the target repo**, e.g. `$TMPDIR/aem-analyser-<label>`.
3. Write artefacts only under `plans/validation/` in this repo.
4. Record the tool commit (`git rev-parse --short HEAD`) in every baseline — findings are only
   comparable when tied to a revision.

## Build

```bash
cd aem-code-analyser
mvn -q -DskipTests package
java -jar aem-analyser-cli/target/aem-analyser-cli.jar -V   # expect: aem-analyser 1.0.0-SNAPSHOT
```

Any build failure is itself a regression: record the error in the baseline file and stop.

## Run

```bash
OUT=/private/var/folders/2d/yvkjbkqx4hj__hy29qgrqtxw0000gp/T/aem-analyser-$(date +%Y%m%d-%H%M)
mkdir -p "$OUT"

java -jar aem-analyser-cli/target/aem-analyser-cli.jar \
  --project-root /path/to/archetype-aem-project \
  --format CONSOLE,SARIF,SONAR,HTML \
  --output "$OUT" \
  --no-color

echo "exit=$?"          # 1 == at least one ERROR (current gate)
ls "$OUT"               # console.log aem-analyser.sarif sonar-aem-analyser-report.json *.html
```

Useful variants:

```bash
# isolate embedded Sonar noise from AEM rules
... --no-sonar

# Maven-profile-sensitive discovery (profiles can disable activeByDefault modules)
... --profile=autoInstallPackage

# fast iteration on a single change set
... --changed-only --since=origin/main
```

## What to capture in a new baseline document

1. Tool commit, version, build command/result, runtime JDK, scan duration.
2. Target repo inventory: modules + packaging, Java file count, OSGi config count **and runmode
   folder list**, HTL count, dispatcher/`.cloudmanager` presence.
3. Exact CLI command and **exit code**.
4. Findings table: rule × severity × count, plus top files.
5. Classification of *every* finding as TP / FP / suspicious, with file:line and a one-line reason.
6. Missed findings you expected (rule should have fired) — these are the most valuable output.
7. Follow-ups mapped to backlog IDs (`C*`, `E*`, `F*`, `O*` in `plans/backlog.md`).

## "No regression" — definition

A wave is **regression-free** only when *all* of the following hold:

| Check | Requirement |
|---|---|
| Build | `mvn -q -DskipTests package` exit 0 |
| Exit code | matches expectation for the accepted findings (currently: any accepted ERROR ⇒ 1) |
| Duplicates | no two violations share `(ruleId, file, startLine, startColumn)`; total count == unique count |
| Accepted TPs | every item in the baseline's "TRUE positives" table still appears at the same location (line drift allowed if the file changed) |
| Confirmed FPs | every item in the baseline's "FALSE POSITIVES" table is **gone** or explicitly re-classified with a new justification |
| New findings | every *new* violation is triaged (TP → fix project or keep, FP → new backlog item). Untriaged new findings block the wave. |
| Platform PIDs | no violation on `com.adobe.*`, `com.day.*`, `org.apache.sling.*`, `org.apache.felix.*` PIDs other than intentional project-owned factory configs |
| Duplicate-config (AEM-DUP-002) | no finding on the same PID across *different runmode folders* (`config` vs `config.dev` is override, not duplication) |
| Cross-boundary sanity | `OsgiConfigs` equals the source `.cfg.json` count (target/ copies excluded); `Modules` equals the reactor POM count including/decluding the root aggregator consistently with the fix landed |

Quick duplicate / count check against SARIF:

```bash
python3 - "$OUT/aem-analyser.sarif" <<'PY'
import json,sys,collections
res=json.load(open(sys.argv[1]))['runs'][0]['results']
keys=[]
for r in res:
    p=r['locations'][0]['physicalLocation']; reg=p.get('region',{})
    keys.append((r['ruleId'],p['artifactLocation']['uri'],reg.get('startLine'),reg.get('startColumn')))
dup=[k for k,v in collections.Counter(keys).items() if v>1]
print("total:",len(keys),"unique:",len(set(keys)),"duplicates:",len(dup))
for d in dup: print("  DUP",d)
print("by rule:",dict(collections.Counter(r['ruleId'] for r in res)))
PY
```

Diff two runs by finding identity (ignore message wording changes):

```bash
python3 - "$OLD/aem-analyser.sarif" "$NEW/aem-analyser.sarif" <<'PY'
import json,sys
def load(p):
    out=set()
    for r in json.load(open(p))['runs'][0]['results']:
        loc=r['locations'][0]['physicalLocation']
        out.add((r['ruleId'],loc['artifactLocation']['uri'],loc.get('region',{}).get('startLine')))
    return out
old,new=load(sys.argv[1]),load(sys.argv[2])
print("FIXED:");   [print("  -",*x) for x in sorted(old-new)]
print("NEW:");     [print("  +",*x) for x in sorted(new-old)]
PY
```

## Cadence

Re-run after every rule/discovery/export change ("wave"), and before any release tag. Keep one
baseline per target project (`portfolio-<label>.md`) plus the shared raw output directory naming above.
When a backlog item is closed, update the affected rows in place and note the tool commit that closed
it, so the baseline doubles as a rule-quality changelog.

## Current state (2026-11-15, wave 1 — C1–C15)

- Build: OK · Scan: **13 violations (5 ERROR / 8 WARNING)** · Exit 1 · **0 duplicate tuples**
- Down from 20 violations (7 ERROR) with 2 duplicate emissions
- All 8 baseline false positives fixed or suppressed; 2 `AEM-SEC-001` remain as policy calls on
  Adobe Core Components boilerplate
- New genuine finding: `AEM-CLOUD-003` × 2 for `getServiceResourceResolver(null)` with no service-user
  mapping (the baseline's highest-value missed defect)
- Controlled edit probe (temporary orphan config) detected exactly once and removed cleanly
