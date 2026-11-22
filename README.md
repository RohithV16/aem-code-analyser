# AEM Code Analyser

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Static analysis for **Adobe Experience Manager** projects: dead code, orphaned OSGi configuration,
duplication and AEM as a Cloud Service problems — across the Java, HTL, OSGi and content boundaries
that single-language tools cannot see.

## Quick start

Pick whichever is easiest — all three need no configuration and no flags.

### 1 · Run it now, nothing to install

```bash
git clone https://github.com/RohithV16/aem-code-analyser
cd aem-code-analyser
scripts/run-from-source.sh ~/my-aem-project
```

The script builds once (Maven, Java 21) and then analyses the path you gave it. Later runs reuse the
build and start instantly. Leave the path out to analyse the directory you are standing in.

### 2 · Or download the jar

```bash
curl -LO https://github.com/RohithV16/aem-code-analyser/releases/latest/download/aem-analyser-cli.jar
cd ~/my-aem-project && java -jar aem-analyser-cli.jar
```

Self-contained; all it needs is Java 21. Run it inside the project and it finds everything by itself.
Every run prints a console report and writes an HTML dashboard to `target/aem-analyser/` by default
(`--no-html` skips the dashboard).

### 3 · Or wire it into the build

```xml
<plugin>
    <groupId>io.github.rohithv16</groupId>
    <artifactId>aem-analyser-maven-plugin</artifactId>
    <!-- latest version: https://github.com/RohithV16/aem-code-analyser/releases -->
    <version>1.0.10</version>
    <executions>
        <execution>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
        </execution>
    </executions>
</plugin>
```

`mvn verify` now fails on new findings. It resolves straight from **Maven Central** — no
`<pluginRepositories>`, no token, nothing to configure.

### No flags needed

| You run it in | Root that gets analysed |
|---|---|
| the reactor root | that directory |
| a module directory (`bundle/`, `ui.apps/`) | the reactor that owns the module |
| any subdirectory without a `pom.xml` | the nearest directory above that has one |
| `java -jar aem-analyser-cli.jar /path` | exactly that path |

Discovery walks past module POMs — a module-only scan cannot see cross-module findings — and stops at
an empty `<relativePath/>`, because that parent comes from a repository.

## What it checks

**38 rules in eight families:** dead code (9), duplication (4), Cloud Manager parity (6),
performance (4), AEM as a Cloud Service (10), security (2), component compatibility (2), workflow
integrity (1). Findings are reported as console output, an HTML dashboard, SARIF 2.1.0, SonarQube
issues, or GitHub/GitLab/Azure annotations.

**→ [Issue reference — every rule, what it detects, how to fix it](https://rohithv16.github.io/aem-code-analyser/issues/)** ·
`aem-analyser --explain AEM-DEAD-001` for the same detail in the terminal.

## Common runs

```bash
java -jar aem-analyser-cli.jar                            # console report + HTML dashboard
java -jar aem-analyser-cli.jar --no-html                  # console only
java -jar aem-analyser-cli.jar --format SARIF --output target/sarif
java -jar aem-analyser-cli.jar --format GITHUB,GITLAB,AZURE   # CI annotations
java -jar aem-analyser-cli.jar --format SONAR             # SonarQube external issues
java -jar aem-analyser-cli.jar --fail-on ERROR            # exit 1 on errors (what the plugin does)
java -jar aem-analyser-cli.jar --collect-coverage         # run tests with fresh JaCoCo data
java -jar aem-analyser-cli.jar --since origin/main        # only files changed in Git
java -jar aem-analyser-cli.jar --ignore-config "ui.config/**/config.publish/**"
java -jar aem-analyser-cli.jar --update-baseline          # accept current findings
java -jar aem-analyser-cli.jar --baseline .aem-analyser-baseline.json
java -jar aem-analyser-cli.jar --explain AEM-DEAD-001     # one rule's rationale
```

A shell alias keeps it short: `alias aem-analyser="java -jar /path/to/aem-analyser-cli.jar"`.

## CLI options reference

Every run writes the HTML dashboard unless `--no-html` is passed.

The dashboard is a single self-contained HTML file (no external assets) with a violations explorer
that supports search, severity/category filters, sortable columns, group-by rule/file, deep links and
per-finding permalinks. It exports the current filtered view as CSV or JSON, copies a PR-ready
Markdown summary, includes a Run Health tab for parser diagnostics and baseline-accepted findings,
and offers a persistent dark mode, print stylesheet and keyboard-accessible tabs.

| Flag | Default | Description |
|---|---|---|
| `-p, --project-root <dir>` | current dir | Reactor root to analyse. |
| `-f, --format <list>` | `CONSOLE` | Comma-separated formats: `CONSOLE,SARIF,SONAR,HTML,GITHUB,GITLAB,AZURE`. |
| `--html [path]` | target path | Generate the HTML dashboard at a custom path. |
| `--no-html` | off | Skip the HTML dashboard for this run. |
| `-o, --output <dir>` | `target/aem-analyser` | Output directory for report files. |
| `-t, --threads <n>` | cores - 1 | Worker threads for parallel parsing. |
| `--profile <list>` | — | Maven profiles to activate during discovery. |
| `--max-file-size <bytes>` | `10485760` | Skip files larger than this size. |
| `--timeout <seconds>` | `0` | Overall analysis budget (0 = none). |
| `--ignore-config <globs>` | — | Reactor-relative config globs to exclude. |
| `--ignored-services <classes>` | — | Service implementation classes never flagged unused. |
| `--dependency-jars <paths>` | — | JARs/directories for external PID suppression. |
| `--config <file>` | `.aem-analyser.yml` | Project configuration YAML. |
| `--severity <RULE=SEVERITY>` | — | Per-rule override for one run (`ERROR`, `WARNING`, `INFO`, `OFF`); repeatable, wins over the config file, unknown ids rejected. |
| `--changed-only` | off | Analyse only files changed in Git. |
| `--since <ref>` | — | Git ref/commit to compare against (implies changed-only). |
| `--fix` | off | Apply safe automated fixes, then re-analyse. |
| `--fix-dry-run` | off | Preview fixes without writing. |
| `--fail-on <severity>` | — | Fail on `ERROR`, `WARNING` or `INFO`. |
| `--fail-on-warning` | off | Legacy alias for `--fail-on WARNING`. |
| `--baseline <file>` | `.aem-analyser-baseline.json` | Baseline of accepted findings. |
| `--update-baseline` | off | Write current findings to the baseline. |
| `--baseline-report` | off | Keep suppressed findings at INFO. |
| `--history <dir>` | — | Append `runs.jsonl` and render the HTML trend. |
| `--history-limit <n>` | `20` | Runs shown in the trend. |
| `--collect-coverage` | off | Run tests with JaCoCo instrumentation. |
| `--force` | off | Force test/coverage re-execution. |
| `--min-coverage <pct>` | — | Quality gate: minimum line coverage. |
| `--max-duplication <pct>` | — | Quality gate: maximum duplication. |
| `--max-warnings <n>` | — | Quality gate: maximum warnings. |
| `--sonar` / `--no-sonar` | on | Toggle embedded SonarJava analysis. |
| `--sonar-rules <list>` | `default` | SonarJava ruleset filter. |
| `--list-rules` | — | Print the effective rule catalogue and exit. `--format json` emits the same catalogue with probe evidence. |
| `--explain <RULE>` | — | Print one rule's effective metadata and exit. |
| `--tag <tag>` | — | With `--list-rules`: only rules carrying the tag. |
| `--diagnose <RULE:PATH:LINE>` | — | Write one redacted, offline repro bundle for a finding and exit. |
| `--import-report <sarif>` | — | Read another analyser's SARIF for migration coverage. |
| `--import-map <file>` | — | Map foreign rule ids to analyser ids (`java:S3649: AEM-QUAL-004`); requires `--import-report`. |
| `--mcp` | off | Serve MCP on stdio for editor agents: `analyse`, `list_rules`, `explain_rule`, `explain_finding`. stdout is protocol-only; logs go to stderr. |
| `--generate-rule-docs <dir>` | — | Generate the issue reference page into `<dir>/issues` and exit. |
| `-v, --verbose` | off | Print analysis diagnostics on stderr. |
| `-q, --quiet` | off | Silence progress and warning lines on stderr; the report on stdout is unaffected. |
| `--no-color` | off | Disable ANSI colours. |

### MCP server for editor agents

`--mcp` serves the [Model Context Protocol](https://modelcontextprotocol.io) over stdio, so Claude
Code, Cursor or Copilot ask the analyser instead of re-deriving AEM semantics. Point your client at
the shaded jar; the project root and the effective configuration are the same ones a gate run uses:

```json
{
  "mcpServers": {
    "aem-analyser": {
      "command": "java",
      "args": ["-jar", "/path/to/aem-analyser-cli.jar", "--mcp", "--project-root", "/path/to/aem-project"]
    }
  }
}
```

| Tool | Argument | Returns |
|---|---|---|
| `analyse` | `root` (optional) | Compact findings (`ruleId`, `severity`, `file`, `line`, `message`, `remediation`, `fingerprint`) plus per-severity counts. |
| `list_rules` | `tag` (optional) | The rule catalogue: id, name, severity, type, tags, CWE, effort. |
| `explain_rule` | `id` | Full metadata and remediation for one rule; unknown ids are JSON-RPC errors. |
| `explain_finding` | `fingerprint` | The finding plus the rule that produced it, using the session's latest `analyse`. |

stdout carries JSON-RPC frames only (one object per line); startup, progress and errors go to stderr,
which `-q` silences. Malformed JSON, unknown methods, unknown tools and unknown rule ids come back as
JSON-RPC error objects and the session keeps serving; closing stdin exits 0.

---

## Maven parameters reference

Both `aem-analyser:check` and `aem-analyser:report` share these parameters.

| Parameter | Default | Description |
|---|---|---|
| `projectRoot` | execution root | Reactor root to analyse. |
| `profiles` | — | Comma-separated Maven profiles for discovery. |
| `threads` | `0` | Worker threads (0 = cores - 1). |
| `maxFileSizeBytes` / `maxFileSize` | `10485760` | Skip files larger than this size. |
| `timeoutSeconds` / `timeout` | `0` | Overall analysis budget (0 = none). |
| `ignoredServices` | — | Comma-separated service classes never flagged unused. |
| `ignoredConfigPaths` | — | Comma-separated config globs to exclude. |
| `dependencyJars` | — | JARs/directories for external PID suppression. |
| `configFile` | `.aem-analyser.yml` | Project configuration YAML. |
| `enableSonar` | `true` | Toggle embedded SonarJava analysis. |
| `sonarRules` | `default` | SonarJava ruleset filter. |
| `formats` / `format` | `CONSOLE` (`report`: `SARIF,SONAR`) | Comma-separated formats. |
| `outputFile` | build dir | Single output file (one format) or output directory. |
| `generateHtml` | `true` | Write the HTML dashboard even when not in `formats`. |
| `skip` | `false` | Skip the goal. |
| `verbose` | `false` | Print analysis diagnostics. |
| `baseline` | — | Baseline JSON of accepted findings. |
| `updateBaseline` | `false` | Write the baseline and gate only new findings. |
| `baselineReport` | `false` | Keep suppressed findings at INFO. |
| `changedOnly` | `false` | Analyse only files changed in Git. |
| `since` | — | Git ref to compare against (implies changed-only). |
| `fix` / `fixDryRun` | `false` | Apply/preview automated fixes before reporting. |
| `failOn` | — | Fail on `ERROR`, `WARNING` or `INFO` (`check` only). |
| `failOnError` | `true` | Fail on any ERROR (`check` only). |
| `failOnWarning` | `false` | Fail on any WARNING (`check` only). |
| `minCoverage` | — | Quality gate: minimum line coverage. |
| `maxDuplication` | — | Quality gate: maximum duplication. |
| `maxWarnings` | — | Quality gate: maximum warnings. |
| `history` | — | Append `runs.jsonl` and render the trend. |
| `historyLimit` | `20` | Runs shown in the trend. |

## Validation probes (deliberate per-rule test code)

`validation/probes` is a self-contained source fixture set, laid out like an AEM reactor, with one
positive case for every rule; `validation/probes-clean` is its fixed twin. It is analyser input rather
than a compilable project, and it exercises the full CLI pipeline rather than isolated unit fixtures.

```bash
# Run the probes directly
scripts/run-rule-probes.sh

# Inject the probes into a temp copy of any AEM repo, run, verify, and clean up.
# The original repository is never modified.
scripts/inject-rule-probes.sh /path/to/aem-project

# Keep the temp copy for inspection
KEEP=1 scripts/inject-rule-probes.sh /path/to/aem-project
```

The checker requires every rule in `validation/probes/expected-rules.txt` to appear in SARIF **on a
probe file**, fails on any rule id missing from that list, and fails if the parser skipped a file
(`*_PARSE_ERROR`). It also reads `validation/probes/evidence.json` and asserts that the declared
positive probe produces exactly one finding while its clean twin produces none, printing one
`PAIR OK` per rule. Injection runs relax only the parser check, because a real repository may contain
genuinely unparseable sources; those diagnostics are still printed. See
[`validation/probes/README.md`](validation/probes/README.md) for the full probe matrix and how to add
a case. The same evidence is published in the jar and at `/evidence/rule-evidence.json`.

### Validation reactor (cross-module behaviour)

`validation/reactor` is a small three-module project (`bundle`, `ui.apps`, `ui.config`) whose findings
are pinned in `validation/reactor/expected-findings.txt`. It covers what a single-file probe cannot:
a `@Reference` injected through a wildcard import, a reference inherited from a shared base class, an
OSGi PID composed from a constant declared in another class, a factory PID with an amendment, and a
clientlib referenced from a sibling module.

```bash
scripts/run-reactor-validation.sh                 # compare against the pinned findings
scripts/run-reactor-validation.sh <jar> --update  # deliberately re-pin after an intended change
```

### Rule documentation

`docs/issues/index.html` — the published issue reference — is generated from `RuleRegistry` and
`RuleFixCatalog`, so it cannot drift from the code:

```bash
scripts/check-rule-docs.sh                            # fails when the page no longer matches the code
java -jar <cli.jar> --generate-rule-docs docs          # regenerate
```

`RuleFixCatalog` switches over `RuleId` exhaustively: adding a rule without writing its explanation
and fix example is a compile error.

---

## Maven plugin in detail

To automatically enforce dead-code quality checks in any AEM project during `mvn clean verify`, add the plugin to the root `pom.xml` of your target AEM project:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.rohithv16</groupId>
            <artifactId>aem-analyser-maven-plugin</artifactId>
            <!-- latest version: https://github.com/RohithV16/aem-code-analyser/releases -->
            <version>1.0.10</version>
            <executions>
                <execution>
                    <id>aem-dead-code-check</id>
                    <phase>verify</phase>
                    <goals>
                        <goal>check</goal>
                    </goals>
                </execution>
            </executions>
            <configuration>
                <!-- Fail build on critical cross-boundary errors (default: true) -->
                <failOnError>true</failOnError>
                <!-- Fail build on warnings (default: false) -->
                <failOnWarning>false</failOnWarning>
                <!-- Report formats: CONSOLE, SARIF, SONAR, HTML (default: CONSOLE) -->
                <formats>CONSOLE</formats>
                <!-- HTML dashboard is generated on every run unless disabled -->
                <generateHtml>true</generateHtml>
                <!-- Optional single output file (with one format) or output directory -->
                <outputFile>${project.build.directory}/aem-analyser-report.txt</outputFile>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### GitHub Actions

The published composite action runs the gate, writes the findings as native annotations and appends
a severity table to the job summary. Adoption is the checkout plus one `uses:` line:

```yaml
- uses: actions/checkout@v5
- uses: RohithV16/aem-code-analyser@v1
```

That fails the job on any ERROR finding. A fuller pipeline — warnings tolerated, a baseline of
accepted findings, and SARIF in the Security tab — is still only a few lines:

```yaml
name: Quality

on:
  pull_request:

permissions:
  contents: read
  security-events: write   # only needed for the SARIF upload

jobs:
  analyse:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: RohithV16/aem-code-analyser@v1
        with:
          fail-on: WARNING
          baseline: .aem-analyser-baseline.json   # optional; relative to the project root
```

| Input | Default | Description |
|---|---|---|
| `fail-on` | `ERROR` | `ERROR`, `WARNING`, `INFO` or `NONE`. `NONE` is report-only and still writes every report. |
| `baseline` | — | Baseline JSON of accepted findings, relative to the project root. |
| `formats` | `GITHUB,SARIF,HTML` | Report formats; `GITHUB` produces the annotations and `SARIF` the code-scanning file. |
| `java-version` | `21` | JDK that runs Maven and the analyser. |
| `version` | `1.0.10` | `aem-analyser-maven-plugin` version the action invokes. |
| `working-directory` | `.` | Project root to analyse, relative to the workspace. |
| `upload-sarif` | `auto` | `auto` probes for code-scanning access, `true` always uploads, `false` never does. |

Outputs: `annotations`, `sarif` and `html` (report paths), plus `errors`, `warnings` and `notices`
counts for downstream steps.

**Version pinning.** The action and the plugin move together: `version` defaults to the plugin
release the action was cut with (`1.0.10` — the first release the action ships against), so a
version tag pins both the action source and the `io.github.rohithv16:aem-analyser-maven-plugin`
coordinate it runs, e.g. `@v1.1.0` would run plugin `1.1.0`. `@v1` is the moving major line whose
`action.yml` carries the matching default. A release bumps that default and cuts the tags; nothing
in the workflow changes. The `v1` tag is published by the first release that contains `action.yml`,
so pin a version tag if you need an immutable ref before then. The SARIF upload needs
`security-events: write` — when the job does not grant it, the action logs a notice and the gate
result stands.

`fail-on: NONE` also passes `-Daem-analyser.failOnError=false` (and `failOnWarning=false`): 1.0.10
evaluates those gates before `failOn`, so `NONE` on its own would still fail the build.

### SonarQube CI/CD Integration
To import the findings into SonarQube without private plugins, configure the plugin to emit SonarQube JSON during your CI build:

```xml
<configuration>
    <formats>SONAR</formats>
    <outputFile>${project.build.directory}/sonar-generic-issues.json</outputFile>
</configuration>
```

And in your `sonar-project.properties`:
```properties
sonar.externalIssuesReportPaths=target/sonar-generic-issues.json
```

---

## Publishing the Repository

### Publishing to GitHub Packages
Add the distribution management repository to root `pom.xml`:
```xml
<distributionManagement>
    <repository>
        <id>github</id>
        <name>GitHub Packages</name>
        <url>https://maven.pkg.github.com/OWNER/aem-code-analyser</url>
    </repository>
</distributionManagement>
```
Deploy using Maven:
```bash
mvn clean deploy -DskipTests
```

### Publishing to Maven Central

Published since **1.0.10** — `io.github.rohithv16:aem-code-analyser`,
`io.github.rohithv16:aem-analyser-core` and `io.github.rohithv16:aem-analyser-maven-plugin`, with
sources, javadoc and GPG signatures. Consumers need no repository entry and no credentials.

The CLI is not published there: its shaded jar bundles every dependency, so it ships as a release
asset instead.

Publishing is **manual and separate from a release**, because Central has a publishing quota and an
irreversible public record:

1. `Actions → Publish to Maven Central → Run workflow` with a release tag (there is a `dry_run`
   switch that only builds and signs).
2. The workflow checks out the tag, refuses a `-SNAPSHOT`, signs and uploads.
3. The Portal validates the bundle and waits — press *Publish* at
   [central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments).

A release on its own only tags, publishes to GitHub Packages and attaches release assets. Signing key,
namespace and secrets are documented in the
[publishing guide](docs/maintainers/).

