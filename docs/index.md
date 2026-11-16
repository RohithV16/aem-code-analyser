---
layout: default
title: AEM Code Analyser
description: Cross-boundary dead-code, duplication and AEM as a Cloud Service analysis for Adobe Experience Manager projects.
---

# AEM Code Analyser

[![Rules](https://img.shields.io/badge/rules-30-blue.svg)](rules/)
[![Formats](https://img.shields.io/badge/reports-7-informational.svg)](#report-formats)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-Apache--2.0-green.svg)](https://github.com/rohithv16/aem-code-analyser/blob/main/LICENSE)

A static analyser for Adobe Experience Manager projects that reasons **across** Java, OSGi
configurations, HTL, client libraries and content packages. It finds code that no longer has a
caller, configuration that no longer has an owner, and patterns that break on AEM as a Cloud Service
— then reports them in the format your CI already understands.

- **Zero configuration** — run it inside the project and it discovers the module layout itself.
- **Cross-boundary** — resolves references between Java, HTL, OSGi configs and dependency JARs.
- **CI native** — console, HTML, SARIF, SonarQube, GitHub, GitLab and Azure reports.
- **Safe by design** — read-only by default, with an explicit `--fix` mode for proven repairs.

---

## Quick start

Three ways in, easiest first. None of them needs configuration, flags or a project root argument.

### 1 · Run it now, nothing to install

```bash
git clone https://github.com/RohithV16/aem-code-analyser
cd aem-code-analyser
scripts/run-from-source.sh ~/my-aem-project
```

Builds once with Maven, then analyses the path you gave it — later runs reuse the build. Leave the
path out to analyse the directory you are standing in.

### 2 · Or download the jar

```bash
curl -LO https://github.com/RohithV16/aem-code-analyser/releases/latest/download/aem-analyser-cli.jar
cd ~/my-aem-project && java -jar aem-analyser-cli.jar
```

Self-contained; all it needs is Java 21. Every run prints a console report and writes an HTML
dashboard to `target/aem-analyser/` (`--no-html` skips it).

### 3 · Or wire it into the build

```xml
<plugin>
  <groupId>io.github.rohithv16</groupId>
  <artifactId>aem-analyser-maven-plugin</artifactId>
  <version>${aem-analyser.version}</version>
  <executions>
    <execution>
      <phase>verify</phase>
      <goals><goal>check</goal></goals>
    </execution>
  </executions>
</plugin>
```

`mvn verify` then fails on new findings. Install routes for the plugin — it is not on JitPack — are in
the [publishing guide](MAVEN_CENTRAL_PUBLISHING_GUIDE.html#0-current-availability-read-this-first).

### No flags needed

| You run it in | Root that gets analysed |
|---|---|
| the reactor root | that directory |
| a module directory (`core/`, `ui.apps/`, `ui.config/`) | the reactor that owns the module |
| any subdirectory without a `pom.xml` | the nearest directory above that has one |
| `--project-root X`, or positional `X` | exactly `X`, with no discovery |

Discovery walks past module POMs — a POM whose declared parent exists on disk is a module, and a
module-only scan cannot see cross-module findings. An empty `<relativePath/>` stops the walk, since
that parent comes from a repository.

### Requirements

| Component | Version |
|---|---|
| Java | 21 |
| Maven | 3.9+ (plugin and source builds only) |
| AEM project layout | any multi-module reactor (`core`, `ui.apps`, `ui.config`, `all`, …) |

---

---

## Report formats

| Format | Output | Use case |
|---|---|---|
| `CONSOLE` | ANSI terminal report | Local runs, quick triage |
| `HTML` | `aem-analyser-report.html` | Shareable dashboard, history trend |
| `SARIF` | `aem-analyser.sarif` | GitHub Code Scanning, Azure DevOps |
| `SONAR` | `sonar-aem-analyser-report.json` | SonarQube `sonar.externalIssuesReportPaths` |
| `GITHUB` | `github-annotations.txt` | Inline GitHub Actions annotations |
| `GITLAB` | `gitlab-code-quality.json` | GitLab Code Quality widget |
| `AZURE` | `azure-pipelines.log` | Azure Pipelines logging commands |

The HTML dashboard includes active violations by severity, an architecture and quality overview,
PMD CPD duplication breakdown with source previews, JaCoCo coverage status, a run-history trend and
the safe remediation plan produced by `--fix`.

The violations explorer supports search, severity and category filters, sortable columns,
group-by-rule/file, deep links and per-finding permalinks. The current filtered view can be exported
as CSV or JSON, or copied as a Markdown summary for a pull request. A **Run Health** tab surfaces
parser/scan diagnostics and baseline-accepted findings, so a silently skipped file can never look
like a clean result. The dashboard is self-contained, has a persistent dark mode, a print stylesheet
and keyboard-accessible tabs.

---

## CLI options

| Flag | Default | Description |
|---|---|---|
| `[project-root]` (positional) | current dir | Shorthand for `--project-root`. |
| `-p, --project-root <dir>` | current dir | Reactor root to analyse; omit it to use the current directory (module directories resolve to their reactor). |
| `-f, --format <list>` | `CONSOLE` | Comma-separated: `CONSOLE,SARIF,SONAR,HTML,GITHUB,GITLAB,AZURE`. |
| `--html [path]` | target path | Generate the HTML dashboard at a custom path. |
| `--no-html` | off | Skip the HTML dashboard. |
| `-o, --output <dir>` | `target/aem-analyser` | Output directory for report files. |
| `-t, --threads <n>` | cores − 1 | Worker threads for parallel parsing. |
| `--profile <list>` | — | Maven profiles to activate during discovery. |
| `--max-file-size <bytes>` | `10485760` | Skip files larger than this size. |
| `--timeout <seconds>` | `0` | Overall analysis budget (0 = none). |
| `--ignore-config <globs>` | — | Reactor-relative config globs to exclude. |
| `--ignored-services <classes>` | — | Service implementations never flagged unused. |
| `--dependency-jars <paths>` | — | JARs/directories for external PID suppression. |
| `--config <file>` | `.aem-analyser.yml` | Project configuration YAML. |
| `--changed-only` | off | Analyse only files changed in Git. |
| `--since <ref>` | — | Git ref to compare against (implies changed-only). |
| `--fix` | off | Apply safe automated fixes, then re-analyse. |
| `--fix-dry-run` | off | Preview fixes without writing. |
| `--fail-on <severity>` | — | Fail on `ERROR`, `WARNING` or `INFO`. |
| `--fail-on-warning` | off | Legacy alias for `--fail-on WARNING`. |
| `--baseline <file>` | `.aem-analyser-baseline.json` | Baseline of accepted findings. |
| `--update-baseline` | off | Write current findings to the baseline. |
| `--baseline-report` | off | Keep suppressed findings at `INFO`. |
| `--history <dir>` | — | Append `runs.jsonl` and render the HTML trend. |
| `--history-limit <n>` | `20` | Runs shown in the trend. |
| `--collect-coverage` | off | Run tests with JaCoCo instrumentation. |
| `--force` | off | Force test/coverage re-execution. |
| `--min-coverage <pct>` | — | Quality gate: minimum line coverage. |
| `--max-duplication <pct>` | — | Quality gate: maximum duplication. |
| `--max-warnings <n>` | — | Quality gate: maximum warnings. |
| `--sonar` / `--no-sonar` | on | Toggle embedded SonarJava analysis. |
| `--sonar-rules <list>` | `default` | SonarJava ruleset filter. |
| `--list-rules` | — | Print the rule catalogue and exit. |
| `--explain <RULE>` | — | Print one rule's metadata and exit. |
| `--generate-rule-docs <dir>` | — | Generate Markdown rule pages and exit. |
| `-v, --verbose` | off | Print analysis diagnostics. |
| `--no-color` | off | Disable ANSI colours. |

### Maven parameters

Both `aem-analyser:check` and `aem-analyser:report` share these parameters.

| Parameter | Default | Description |
|---|---|---|
| `projectRoot` | execution root | Reactor root to analyse. |
| `profiles` | — | Comma-separated Maven profiles for discovery. |
| `threads` | `0` | Worker threads (0 = cores − 1). |
| `maxFileSizeBytes` / `maxFileSize` | `10485760` | Skip files larger than this size. |
| `timeoutSeconds` / `timeout` | `0` | Overall analysis budget. |
| `ignoredServices` | — | Service classes never flagged unused. |
| `ignoredConfigPaths` | — | Config globs to exclude. |
| `dependencyJars` | — | JARs/directories for external PID suppression. |
| `configFile` | `.aem-analyser.yml` | Project configuration YAML. |
| `enableSonar` | `true` | Toggle embedded SonarJava analysis. |
| `sonarRules` | `default` | SonarJava ruleset filter. |
| `formats` / `format` | `CONSOLE` (`report`: `SARIF,SONAR`) | Comma-separated formats. |
| `outputFile` | build dir | Single output file or output directory. |
| `generateHtml` | `true` | Write the HTML dashboard even when not in `formats`. |
| `skip` | `false` | Skip the goal. |
| `verbose` | `false` | Print analysis diagnostics. |
| `baseline` | — | Baseline JSON of accepted findings. |
| `updateBaseline` | `false` | Write the baseline, gate only new findings. |
| `baselineReport` | `false` | Keep suppressed findings at `INFO`. |
| `changedOnly` | `false` | Analyse only files changed in Git. |
| `since` | — | Git ref to compare against. |
| `fix` / `fixDryRun` | `false` | Apply/preview automated fixes. |
| `failOn` | — | Fail on `ERROR`, `WARNING` or `INFO` (`check` only). |
| `failOnError` | `true` | Fail on any ERROR (`check` only). |
| `failOnWarning` | `false` | Fail on any WARNING (`check` only). |
| `minCoverage` | — | Quality gate: minimum line coverage. |
| `maxDuplication` | — | Quality gate: maximum duplication. |
| `maxWarnings` | — | Quality gate: maximum warnings. |
| `history` | — | Append `runs.jsonl` and render the trend. |
| `historyLimit` | `20` | Runs shown in the trend. |

---

## Configuration

### Project file — `.aem-analyser.yml`

```yaml
schemaVersion: 1
rules:
  AEM-DEAD-003B:
    enabled: false
  AEM-SEC-001:
    severity: WARNING
allowlists:
  services:
    - com.example.LegacyService
  configPaths:
    - ui.config/**/config.local/**
```

Rule overrides are applied after analysis, so severity changes flow into every report and into the
exit code. `--config` points at a different file.

### Baseline — `.aem-analyser-baseline.json`

Freeze findings that are already accepted, then gate only regressions:

```bash
aem-analyser --project-root . --update-baseline
aem-analyser --project-root . --baseline .aem-analyser-baseline.json
aem-analyser --project-root . --baseline-report   # keep suppressed findings visible at INFO
```

### Inline suppression

```java
// aem-analyser-disable-next-line AEM-DEAD-001
```
```html
<!-- aem-analyser-disable-next-line AEM-SEC-001 -->
```
```java
// aem-analyser-disable-file AEM-DUP-010
```

### Scoping a run

```bash
# Only files changed since a Git ref
aem-analyser --project-root . --since origin/main

# Exclude generated or environment-specific configuration
aem-analyser --project-root . --ignore-config "ui.config/**/config.publish/**"

# Recognise PIDs that live in dependency bundles
aem-analyser --project-root . --dependency-jars ~/.m2/repository/com/vendor
```

---

## CI integration

### GitHub Actions with Code Scanning

{% raw %}
```yaml
- name: AEM analysis
  run: |
    java -jar aem-analyser-cli/target/aem-analyser-cli.jar \
      --project-root . --format SARIF --no-color

- name: Upload SARIF
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: target/aem-analyser/aem-analyser.sarif
```
{% endraw %}

### SonarQube

```bash
aem-analyser --project-root . --format SONAR --output target/sonar

sonar-scanner \
  -Dsonar.externalIssuesReportPaths=target/sonar/sonar-aem-analyser-report.json
```

### Quality gates

```bash
aem-analyser --project-root . \
  --fail-on ERROR \
  --min-coverage 60 --max-duplication 5 --max-warnings 0
```

Exit codes: `0` clean, `1` findings at or above the configured threshold, `2` analysis failure.
`--fail-on NONE` is report-only: it exits `0` with findings and says so on stdout.

---

## Rule catalogue

**30 rules in seven families.** Every rule has its own page with the detection logic, the AEM as a
Cloud Service impact and the remediation:

**→ [Browse the full rule catalogue](rules/)** — or `aem-analyser --explain <RULE-ID>` for the same
detail in the terminal.

| Family | Rules | Focus |
|---|---|---|
| DEAD | 8 | Code, configuration and content with no remaining consumer |
| DUP | 4 | Duplicate endpoints, configuration, bindings and code blocks |
| QUAL | 6 | Cloud Manager quality checks and thread safety (`AEM-QUAL-006` retired, owned by `-004`) |
| PERF | 4 | Resource lifecycles and repository query cost |
| CLOUD | 5 | Cloud Service compatibility and secrets |
| SEC | 2 | Output escaping and request-driven redirects |
| COMP | 1 | Deprecated Classic UI dialogs |

Where Adobe Cloud Manager has an equivalent check, the rule id says so (`AEM-QUAL-001` ↔ CQBP-75,
`-002` ↔ CQBP-84, `-003` ↔ AMSCORE-554, `-005` ↔ AMSCORE-553), so existing waivers keep their meaning.

---

## Validation harness

Every active rule is verified by a deliberate probe — a fixture that must produce a finding — rather
than only by unit tests:

```bash
scripts/run-rule-probes.sh                       # expects 29/29 active rules
scripts/inject-rule-probes.sh /path/to/aem-repo  # runs probes inside a temp copy of a real repo
```

The injection script `rsync`s the target into a temporary directory, so the original repository is
never modified. The checker fails when a rule is missing, fires only on non-probe code, has no probe
in the catalogue, or when the parser silently skipped a file. Retired rules (AEM-QUAL-006) are
declared disabled in SARIF and exempt; AEM-DUP-010 is exempt from the evaluator check because it is
produced by the duplication engine.

### Cross-module reactor

Single-file probes cannot express behaviour that only exists across a reactor, so
`validation/reactor` is a real three-module project (`bundle`, `ui.apps`, `ui.config`) with its
findings pinned:

```bash
scripts/run-reactor-validation.sh                 # compare against validation/reactor/expected-findings.txt
scripts/run-reactor-validation.sh <jar> --update  # re-pin after an intended change
```

It covers injection through a wildcard import, a `@Reference` declared on a shared base class, an
OSGi PID composed from a constant in another class, a factory PID with an amendment, and a clientlib
referenced from a sibling module — the cases that produced real defects in earlier releases.

### Rule documentation

`docs/rules/*.md` are generated from `RuleRegistry`, so a page cannot drift from the code:

```bash
scripts/check-rule-docs.sh                                        # fails when a page is stale
java -jar aem-analyser-cli.jar --generate-rule-docs docs/rules    # regenerate
```

All three checks run in CI, together with the unit, integration and quality jobs on Linux, macOS and
Windows.

---

## Documentation

- [Rule pages](rules/) — detection logic and remediation for all 30 rules
- [Releases and download assets](https://github.com/RohithV16/aem-code-analyser/releases)
- [Maven publishing guide](MAVEN_CENTRAL_PUBLISHING_GUIDE.html)
- [Full CLI and Maven reference](https://github.com/rohithv16/aem-code-analyser#cli-options-reference)
- [Contributing](https://github.com/rohithv16/aem-code-analyser/blob/main/CONTRIBUTING.md)
- [Security policy](https://github.com/rohithv16/aem-code-analyser/blob/main/SECURITY.md)
- [Changelog](https://github.com/rohithv16/aem-code-analyser/blob/main/CHANGELOG.md)
