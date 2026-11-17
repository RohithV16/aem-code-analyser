# Contributing

## Prerequisites

- JDK 21 (the build targets Java 21; newer JDKs can break JaCoCo 0.8.12 instrumentation)
- Maven 3.9+

```bash
export JAVA_HOME=/path/to/jdk-21
mvn clean verify
```

## Project layout

| Module | Purpose |
|---|---|
| `aem-analyser-core` | Discovery, Java/config/HTL parsing, reference graph, rules, exporters, baseline |
| `aem-analyser-cli` | Picocli executable (`--format`, `--fix`, `--baseline`, `--changed-only`) |
| `aem-analyser-maven-plugin` | `aem-analyser:check` and `aem-analyser:report` goals |

## Adding or changing a rule

1. Implement `RuleEvaluator` in `core/rules/impl` and add it to `RuleCatalog` (the registry is
   validated against `RuleId` at startup, so a missing or duplicate producer fails immediately).
2. Add the id (and default severity) to `model/report/RuleId`.
3. Add at least one true-positive and one false-positive-guard fixture.
4. Run `scripts/run-rule-probes.sh` and the validation project before opening a PR.
5. Update `README.md`, regenerate the issue reference (`--generate-rule-docs docs`) and add a
   `CHANGELOG.md` entry.

## Gates to run before a PR

```bash
mvn -B -ntp clean verify           # unit, integration and exporter tests
scripts/run-rule-probes.sh         # every active rule must fire on its probe
scripts/run-reactor-validation.sh  # pinned findings for validation/reactor
scripts/check-rule-docs.sh         # docs/rules must match the generator
```

All four run in CI (`ci.yml`: verify matrix, Invoker ITs, validation, quality). A new rule needs a
probe fixture in `validation/probes` **and** an entry in its `expected-rules.txt`; a rule that changes
behaviour needs the reactor's `expected-findings.txt` re-pinned deliberately
(`scripts/run-reactor-validation.sh <jar> --update`), with the reason in the PR.

## Tests

- Unit tests live next to the code under `src/test/java`.
- End-to-end fixtures use `@TempDir` reactors (`EndToEndAnalysisTest`, `AnalysisDiagnosticsTest`).
- Every bug fix should come with a regression test that fails before the change.

## Style

- Java 21, 4-space indentation, no wildcard imports.
- Public API changes must keep source compatibility where practical (add overloads rather than
  changing record components).
- Records are used for immutable model types; keep them free of mutable state.

## Changelog

`CHANGELOG.md` is the record of what shipped and why; there is no separate backlog file. Add an
entry under *Unreleased* in the same PR as the change.
