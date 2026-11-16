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
5. Update `README.md`, `docs/rules/<RULE-ID>.md` and `CHANGELOG.md`.

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
