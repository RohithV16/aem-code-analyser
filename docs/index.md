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

- **Zero configuration** — point it at a reactor root and it discovers the module layout.
- **Cross-boundary** — resolves references between Java, HTL, OSGi configs and dependency JARs.
- **CI native** — console, HTML, SARIF, SonarQube, GitHub, GitLab and Azure reports.
- **Safe by design** — read-only by default, with an explicit `--fix` mode for proven repairs.

---

## Quick start

### Standalone CLI

```bash
git clone https://github.com/rohithv16/aem-code-analyser.git
cd aem-code-analyser
mvn -q -DskipTests package

java -jar aem-analyser-cli/target/aem-analyser-cli.jar \
  --project-root /path/to/aem-project
```

Every run writes a console report **and** an HTML dashboard to
`target/aem-analyser/aem-analyser-report.html` unless `--no-html` is passed.

### Maven plugin

```xml
<plugin>
  <groupId>io.github.rohithv16</groupId>
  <artifactId>aem-analyser-maven-plugin</artifactId>
  <version>1.0.7</version>
  <executions>
    <execution>
      <phase>verify</phase>
      <goals><goal>check</goal></goals>
    </execution>
  </executions>
</plugin>
```

### Requirements

| Component | Version |
|---|---|
| Java | 21 |
| Maven | 3.9+ |
| AEM project layout | any multi-module reactor (`core`, `ui.apps`, `ui.config`, `all`, …) |

---

## What it analyses

| Area | Coverage |
|---|---|
| **Dead code** | Orphaned OSGi configs, ghost config properties, unconsumed OCD attributes, unused services, unused Sling Models, inactive delayed components, broken HTL references, orphaned client libraries |
| **Duplication** | Duplicate servlet endpoints, conflicting OSGi configs, competing Sling Models, cloned method blocks (AST-based) |
| **AEM Cloud readiness** | Hardcoded secrets, JSP usage, unmapped service users, deprecated APIs, invalid runmode folders, Classic UI dialogs |
| **Correctness & quality** | Path-bound servlets (CQBP-75), `@ProviderType` implementations (CQBP-84), whiteboard schedulers, unsafe component state, administrative access, resolver state leaks |
| **Performance** | Unclosed resolvers and sessions, Oak traversal queries, unbounded result sets |
| **Security** | HTL `context='unsafe'`, open redirects and SSRF |

Reference IDs map to Adobe Cloud Manager checks where one exists (e.g. `AEM-QUAL-001` ↔ CQBP-75),
so existing waivers and documentation keep their meaning.

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
| `-p, --project-root <dir>` | current dir | Reactor root to analyse. |
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

**30 rules** across seven families. Each rule page documents the detection logic, why it matters on
AEM as a Cloud Service, and the remediation.

| Family | Rules | Focus |
|---|---|---|
| DEAD | 8 | Code, configuration and content with no remaining consumer |
| DUP | 4 | Duplicate endpoints, configuration, bindings and code blocks |
| QUAL | 6 | Cloud Manager quality checks and thread-safety |
| PERF | 4 | Resource lifecycles and repository query cost |
| CLOUD | 5 | Cloud Service compatibility and secrets |
| SEC | 2 | Output escaping and request-driven redirects |
| COMP | 1 | Deprecated Classic UI dialogs |

### Dead code

| Rule | Severity | Summary |
|---|---|---|
| [AEM-DEAD-001](rules/AEM-DEAD-001.html) | ERROR | Orphaned OSGi configuration without a matching component or platform PID. |
| [AEM-DEAD-002A](rules/AEM-DEAD-002A.html) | WARNING | Configuration property does not exist in the target `@ObjectClassDefinition`. |
| [AEM-DEAD-002B](rules/AEM-DEAD-002B.html) | WARNING | OCD attribute method is never consumed by the designating component. |
| [AEM-DEAD-003A](rules/AEM-DEAD-003A.html) | WARNING | OSGi service has zero injections and is not a system entry point. |
| [AEM-DEAD-003B](rules/AEM-DEAD-003B.html) | INFO | Sling Model has no Java adaptation and no `resourceType` binding. |
| [AEM-DEAD-003C](rules/AEM-DEAD-003C.html) | WARNING | Component provides no service interfaces and `immediate = false`. |
| [AEM-DEAD-004](rules/AEM-DEAD-004.html) | ERROR | HTL references a non-existent Java class or Sling Model. |
| [AEM-DEAD-005](rules/AEM-DEAD-005.html) | INFO | Client library category is never referenced by a template or another client library. |

### Duplication

| Rule | Severity | Summary |
|---|---|---|
| [AEM-DUP-001](rules/AEM-DUP-001.html) | ERROR | Multiple servlets claim identical paths or resource type selectors. |
| [AEM-DUP-002](rules/AEM-DUP-002.html) | WARNING | Conflicting OSGi configuration for the same PID and runmode. |
| [AEM-DUP-003](rules/AEM-DUP-003.html) | WARNING | Multiple Sling Models bind to the same resource type and adapter. |
| [AEM-DUP-010](rules/AEM-DUP-010.html) | WARNING | Duplicate method or code block detected across classes. |

### Quality

| Rule | Severity | Summary |
|---|---|---|
| [AEM-QUAL-001](rules/AEM-QUAL-001.html) | WARNING | Servlet registered by path instead of resource type (CQBP-75). |
| [AEM-QUAL-002](rules/AEM-QUAL-002.html) | ERROR | Custom code implements a product `@ProviderType` (CQBP-84). |
| [AEM-QUAL-003](rules/AEM-QUAL-003.html) | WARNING | Whiteboard scheduler used instead of Sling Scheduled Jobs (AMSCORE-554). |
| [AEM-QUAL-004](rules/AEM-QUAL-004.html) | ERROR | Non-thread-safe object stored in an OSGi component field. |
| [AEM-QUAL-005](rules/AEM-QUAL-005.html) | ERROR | Deprecated administrative login or resolver API (AMSCORE-553). |
| [AEM-QUAL-006](rules/AEM-QUAL-006.html) | WARNING | Request-scoped resolver or session stored in an instance field. |

### Performance

| Rule | Severity | Summary |
|---|---|---|
| [AEM-PERF-001](rules/AEM-PERF-001.html) | ERROR | Resolver obtained from a factory is never closed. |
| [AEM-PERF-002](rules/AEM-PERF-002.html) | ERROR | JCR session obtained from a repository is never closed. |
| [AEM-PERF-003](rules/AEM-PERF-003.html) | WARNING | Query lacks a path or node type restriction (Oak traversal). |
| [AEM-PERF-004](rules/AEM-PERF-004.html) | WARNING | Query has no limit and can scan an unbounded result set. |

### Cloud Service

| Rule | Severity | Summary |
|---|---|---|
| [AEM-CLOUD-001](rules/AEM-CLOUD-001.html) | ERROR | Plaintext secret or password in an OSGi configuration. |
| [AEM-CLOUD-002](rules/AEM-CLOUD-002.html) | WARNING | Legacy JSP file; AEMaaCS requires HTL. |
| [AEM-CLOUD-003](rules/AEM-CLOUD-003.html) | ERROR | Service resolver has no matching service-user mapping. |
| [AEM-CLOUD-004](rules/AEM-CLOUD-004.html) | WARNING | API deprecated in AEM as a Cloud Service. |
| [AEM-CLOUD-005](rules/AEM-CLOUD-005.html) | WARNING | Configuration in an unrecognised runmode folder. |

### Security and components

| Rule | Severity | Summary |
|---|---|---|
| [AEM-SEC-001](rules/AEM-SEC-001.html) | ERROR | HTL expression bypasses escaping with `context='unsafe'`. |
| [AEM-SEC-002](rules/AEM-SEC-002.html) | WARNING | User-controlled input flows into a redirect or outbound request. |
| [AEM-COMP-001](rules/AEM-COMP-001.html) | WARNING | Component defines a deprecated Classic UI `dialog.xml`. |

---

## Validation harness

Every rule is verified by a deliberate probe — a fixture that must produce a finding — rather than
only by unit tests:

```bash
scripts/run-rule-probes.sh                       # expects 30/30
scripts/inject-rule-probes.sh /path/to/aem-repo  # runs probes inside a temp copy of a real repo
```

The injection script `rsync`s the target into a temporary directory, so the original repository is
never modified. The checker fails when a rule is missing, fires only on non-probe code, has no probe
in the catalogue, or when the parser silently skipped a file.

The harness was validated independently against a large multi-project monorepo and a second
production repository; both reported 30/30 with the originals unchanged.

---

## Documentation

- [Rule pages](rules/) — detection logic and remediation for all 30 rules
- [Maven Central publishing guide](MAVEN_CENTRAL_PUBLISHING_GUIDE.html)
- [Full CLI and Maven reference](https://github.com/rohithv16/aem-code-analyser#cli-options-reference)
- [Contributing](https://github.com/rohithv16/aem-code-analyser/blob/main/CONTRIBUTING.md)
- [Security policy](https://github.com/rohithv16/aem-code-analyser/blob/main/SECURITY.md)
- [Changelog](https://github.com/rohithv16/aem-code-analyser/blob/main/CHANGELOG.md)
