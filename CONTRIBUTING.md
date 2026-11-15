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

1. Implement `RuleEvaluator` in `core/rules/impl` and register it in `DefaultAemCodeAnalyser.rules`.
2. Add the id (and default severity) to `model/report/RuleId`.
3. Add at least one true-positive and one false-positive-guard fixture.
4. Run the real-project validation in `plans/validation/` and record the result.
5. Update `README.md` and the relevant plan file under `plans/`.

## Tests

- Unit tests live next to the code under `src/test/java`.
- End-to-end fixtures use `@TempDir` reactors (`EndToEndAnalysisTest`, `AnalysisDiagnosticsTest`).
- Every bug fix should come with a regression test that fails before the change.

## Style

- Java 21, 4-space indentation, no wildcard imports.
- Public API changes must keep source compatibility where practical (add overloads rather than
  changing record components).
- Records are used for immutable model types; keep them free of mutable state.

## Plans

The backlog is stored in `plans/`. When you close an item, update its status row in
`plans/README.md` and append validation evidence to `plans/validation/`.
