# P1 — Edge-case hardening index

Status: Proposed
Priority: P1
Area: plans/p1 (index)
Problem / opportunity: `plans/backlog.md` lists E1–E15 as one-line groups. They are not
implementation-ready: no file-level anchors, no regression fixtures, no "already handled" notes.
Desired behavior: one plan per E id, grouped by subsystem, each with concrete steps and tests.
Files to change: `plans/p1/E1-*.md` … `plans/p1/E15-*.md` (this index links them).
Implementation steps:
1. Land P0 wave 1 (C1–C5, C8) first — E1/E2/E7 depended on the PID resolver and the
   `AEM-DEAD-002A` aggregation fix; re-check each plan after that wave.
2. Treat each E plan as a vertical slice: code + fixture + parameterized test.
3. Re-run `plans/validation/portfolio-baseline.md` after every E item.
Tests to add: no tests in the index; see the per-item plans.
Acceptance criteria: every E1–E15 has a plan file with the 9 template sections.
Risks / notes:
- `plans/README.md` status board links to `plans/p0/C1-*.md` … `C9-*.md`; P0 plans now exist and
  C1–C9 have been implemented in wave 1. Re-check each E plan against the landed code before starting
  it, because E1/E2/E7 depended on the PID resolver and the `AEM-DEAD-002A` aggregation fix.

## Group 1 — OSGi rule semantics

| ID | Title | Status | File |
|---|---|---|---|
| E1 | `AEM-DEAD-001` PID semantics (configPid, `$`, IGNORE, factories, platform PIDs) | Verified | [E1](E1-osgi-pid-semantics.md) |
| E2 | `AEM-DEAD-002A` metatype matching, whitelist, typed keys | Verified | [E2](E2-dead-002a-metatype-matching.md) |
| E3 | `AEM-DEAD-002B` property-consumption coverage | Verified | [E3](E3-dead-002b-consumption.md) |
| E4 | `AEM-DEAD-003A` service provision, references, exports | Verified (mostly done) | [E4](E4-dead-003a-services.md) |
| E5 | `AEM-DEAD-003B` HTL/JCR/model reference coverage | Verified | [E5](E5-dead-003b-htl-models.md) |
| E6 | `AEM-DEAD-003C` DS activation semantics | Done | [E6](E6-dead-003c-activation.md) |

## Group 2 — Config parsing

| ID | Title | Status | File |
|---|---|---|---|
| E7 | Config parser fidelity (duplicates, BOM, nesting, `.content.xml`, runmode merge) | Verified | [E7](E7-config-parsing.md) |

## Group 3 — Discovery

| ID | Title | Status | File |
|---|---|---|---|
| E8 | Reactor discovery + module classification edge cases | Done (core semantics) | [E8](E8-discovery.md) |

## Group 4 — Reporting / CLI / Maven

| ID | Title | Status | File |
|---|---|---|---|
| E9 | `GitDiffResolver` path and range correctness | Done | [E9](E9-git-diff.md) |
| E10 | SARIF/Sonar schema conformance and metadata | Verified | [E10](E10-sarif-sonar.md) |
| E11 | Structured analysis diagnostics instead of silent skips | Done | [E11](E11-diagnostics.md) |
| E12 | CLI argument validation and testability | Done (seam deferred) | [E12](E12-cli-contract.md) |
| E13 | Maven parameter parity + Invoker ITs | Done | [E13](E13-maven-mojos.md) |
| E14 | `dependencyJarRoots` default and `--dependency-jars` | Done | [E14](E14-dependency-jars.md) |
| E15 | HTML report escaping, self-containment, size bound | Done | [E15](E15-html-escaping.md) |
