# AEM Code Analyser — Plan Store

This directory is the single source of truth for the remediation and feature backlog of
`aem-code-analyser`. It is deliberately kept inside the repository so plans, code, and
validation evidence live together.

## Layout

```
plans/
  README.md              # this file: conventions + status board
  backlog.md             # full prioritized backlog (P0/P1/P2)
  p0/                    # correctness bugs / release blockers (one file per item)
  p1/                    # edge-case hardening
  p2/                    # features, performance, test & release engineering
  validation/            # real-world validation against external AEM repositories
```

## Status legend

| Status | Meaning |
|---|---|
| `Proposed` | Identified, not yet validated against code/tests |
| `Verified` | Root cause confirmed by reading code or reproducing |
| `Planned` | Implementation steps + tests written down |
| `In progress` | Branch/worktree has changes |
| `Done` | Code + test merged; acceptance criteria met |
| `Won't do` | Rejected with rationale recorded |

## Conventions

- Every plan file uses the same template: **Problem → Desired behavior → Files → Steps → Tests → Acceptance → Risks**.
- Rule IDs reference `RuleId` constants (e.g. `AEM-DEAD-001`), not Java class names, in user-facing text.
- A plan is only `Done` when `mvn clean verify` is green **and** the real-world validation run
  (`plans/validation/portfolio-baseline.md`) shows no new false positives.
- Cross-module changes must keep `aem-analyser-cli`, `aem-analyser-maven-plugin`, and
  `aem-analyser-core` API-compatible unless the plan explicitly bumps the API.
- New behavior gets a test in the module that owns it; end-to-end behavior gets a fixture in
  `aem-analyser-core/src/test/resources`.

## Status board

| ID | Title | Priority | Status | File |
|---|---|---|---|---|
| C1 | `ignoredConfigPaths` is never consumed | P0 | Done | [p0/C1-ignored-config-paths.md](p0/C1-ignored-config-paths.md) |
| C2 | Runmode index key mismatch | P0 | Done | [p0/C2-runmode-index.md](p0/C2-runmode-index.md) |
| C3 | PID grammar rejects hyphens | P0 | Done | [p0/C3-pid-hyphen-grammar.md](p0/C3-pid-hyphen-grammar.md) |
| C4 | `AEM-DEAD-002A` only checks first component / wrong normalizer | P0 | Done | [p0/C4-dead-002a-multi-component.md](p0/C4-dead-002a-multi-component.md) |
| C5 | `AEM-DUP-010` duplicated by dead rule + PMD CPD | P0 | Done | [p0/C5-dup-010-single-source.md](p0/C5-dup-010-single-source.md) |
| C6 | `--fix` produces stale report/exit code | P0 | Done | [p0/C6-stale-report-after-fix.md](p0/C6-stale-report-after-fix.md) |
| C7 | Maven/CLI docs advertise parameters that do not exist | P0 | Done | [p0/C7-maven-cli-contract.md](p0/C7-maven-cli-contract.md) |
| C8 | `ambiguousHyphen` computed but never used | P0 | Done | [p0/C8-ambiguous-hyphen.md](p0/C8-ambiguous-hyphen.md) |
| C9 | Missing `LICENSE` despite README badge | P0 | Done | [p0/C9-license.md](p0/C9-license.md) |
| C10 | Platform PID prefix matches across segment boundaries | P0 | Done | [p0/C10-platform-pid-boundary.md](p0/C10-platform-pid-boundary.md) |
| C11 | `.content.xml` PIDs carried a `.content` suffix | P0 | Done | [p0/C11-content-xml-pid.md](p0/C11-content-xml-pid.md) |
| C12 | Reactor root scanned as content module (duplicate findings) | P0 | Done | [p0/C12-duplicate-htl-scan.md](p0/C12-duplicate-htl-scan.md) |
| C13 | Resource lifecycle false positives (`AEM-PERF-001/002`) | P0 | Done | [p0/C13-resource-lifecycle-fp.md](p0/C13-resource-lifecycle-fp.md) |
| C14 | `AEM-CLOUD-003` missed `getServiceResourceResolver(null)` | P0 | Done | [p0/C14-cloud-003-default-subservice.md](p0/C14-cloud-003-default-subservice.md) |
| C15 | AEM-aware SonarJava false-positive suppressions | P0 | Done | [p0/C15-sonar-aem-suppressions.md](p0/C15-sonar-aem-suppressions.md) |
| C16 | Generic-repo hardening (multi-project validation) | P0 | Done | [p0/C16-generic-repo-hardening.md](p0/C16-generic-repo-hardening.md) |

## Validation evidence

| Report | Scope | Status |
|---|---|---|
| [rule-probe-validation.md](validation/rule-probe-validation.md) | Deliberate per-rule probes, run directly and injected into two real repositories (three independent reviewers) | Done |
| [portfolio-baseline.md](validation/portfolio-baseline.md) | Real-project baseline and false-positive triage | Done |

## Sequencing

1. **Wave 1 — correctness (this repo):** C1–C5, C8 + golden tests. Restores trust in rule output.
2. **Wave 2 — delivery contract:** C6, C7, C9 + CLI/Maven parity. Makes CI adoption honest.
3. **Wave 3 — edge cases:** E1–E15 from `backlog.md`.
4. **Wave 4 — features/performance:** F1–F10, O1–O8.

Real-world validation is run after every wave against
`/path/to/archetype-aem-project` (see `plans/validation/`).
