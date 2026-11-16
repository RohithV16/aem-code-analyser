# AEM Code Analyser

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A static code analysis engine specifically designed for **Adobe Experience Manager (AEM)** projects to detect **cross-boundary dead code, orphaned OSGi configurations, and unreferenced architectural symbols**.

Traditional static analyzers (SonarQube, SpotBugs, PMD) operate in single-language silos and cannot trace connections between Java Declarative Services, OSGi `.cfg.json` configurations, HTL templates, and JCR nodes. AEM Code Analyser builds a unified **Cross-Boundary Reference Graph** to identify dead code across polyglot boundaries.

---

## Features

**30 rule ids across seven families.** Every rule page documents detection logic, AEM Cloud Service
impact and remediation — see [docs/rules](docs/rules) or `--explain <RULE>`.

| Family | Rules | What it finds |
|---|---|---|
| **DEAD** | 8 | Orphaned OSGi configs (`-001`), ghost config properties (`-002A`), unconsumed OCD attributes (`-002B`), unused services and Sling Models and inactive delayed components (`-003A/B/C`), HTL references to missing classes (`-004`), orphaned client libraries (`-005`) |
| **DUP** | 4 | Duplicate servlet endpoints (`-001`), conflicting OSGi configs (`-002`), competing Sling Models (`-003`), cloned code blocks via PMD CPD (`-010`) |
| **QUAL** | 6 | Cloud Manager parity: path-bound servlets CQBP-75 (`-001`), `@ProviderType` implementations CQBP-84 (`-002`), whiteboard schedulers AMSCORE-554 (`-003`), non-thread-safe component fields (`-004`), administrative logins AMSCORE-553 (`-005`); `-006` retired, owned by `-004` |
| **PERF** | 4 | Unclosed resolvers (`-001`), unclosed sessions (`-002`), unbounded Oak traversal (`-003`), queries without a limit (`-004`) |
| **CLOUD** | 5 | Plaintext secrets (`-001`), JSP usage (`-002`), service users without a mapping (`-003`), APIs deprecated in AEMaaCS (`-004`), invalid runmode folders (`-005`) |
| **SEC** | 2 | `context='unsafe'` in HTL (`-001`), request-driven redirects and SSRF (`-002`) |
| **COMP** | 1 | Deprecated Classic UI `dialog.xml` (`-001`) |

Reports: **CONSOLE**, **HTML** dashboard (history trend, duplication and coverage panels),
**SARIF 2.1.0**, **SonarQube** generic issues, and **GitHub**, **GitLab** and **Azure** annotations.

---

## Install

| Requirement | Version |
|---|---|
| Java | 21 |
| Maven (plugin and source builds only) | 3.9+ |

| Artefact | Route |
|---|---|
| CLI | `aem-analyser-cli.jar` from [Releases](https://github.com/RohithV16/aem-code-analyser/releases) — self-contained, needs only Java 21 |
| `aem-analyser-core` (library) | [JitPack](https://jitpack.io/#RohithV16/aem-code-analyser), no token: `com.github.RohithV16.aem-code-analyser:aem-analyser-core:<tag>` |
| `aem-analyser-maven-plugin` | GitHub Packages (needs a `read:packages` token) **or** offline install from the release assets |

The plugin is deliberately **not** documented for JitPack: JitPack relocates the groupId, and Maven
rejects a plugin whose descriptor declares a different group (`Invalid plugin descriptor ... wrong
group ID`). Install the attached jars instead — no token, no registry access:

```bash
VERSION=<version>   # from the release page
mvn install:install-file -Dfile=aem-code-analyser-$VERSION.pom \
    -DpomFile=aem-code-analyser-$VERSION.pom
mvn install:install-file -Dfile=aem-analyser-core-$VERSION.jar \
    -DpomFile=aem-analyser-core-$VERSION.pom
mvn install:install-file -Dfile=aem-analyser-maven-plugin-$VERSION.jar \
    -DpomFile=aem-analyser-maven-plugin-$VERSION.pom
```

Building from source instead:

```bash
git clone https://github.com/RohithV16/aem-code-analyser
cd aem-code-analyser && mvn clean install
```

---

## Usage Option 1: Standalone CLI

Run audits directly on any AEM repository. **The project root is optional** — with no arguments the
analyser looks at where you are:

```bash
java -jar aem-analyser-cli.jar                      # current directory, or the reactor that owns it
java -jar aem-analyser-cli.jar .                    # explicit, same thing
java -jar aem-analyser-cli.jar --project-root ../aem-project
```

| You run it in | Root that gets analysed |
|---|---|
| the reactor root | that directory |
| a module directory (`bundle/`, `ui.apps/`) | the reactor that owns the module |
| any subdirectory without a `pom.xml` | the nearest directory above that has one |
| `--project-root X` / positional `X` | exactly `X`, no discovery |

Discovery walks past module POMs — a POM whose declared parent exists on disk is a module, and a
module-only scan cannot see cross-module findings. An empty `<relativePath/>` stops the walk, because
that parent is resolved from a repository rather than from the directory above.

### Running without downloading a jar

```bash
# 1. Straight from a clone: builds only when the sources changed, then analyses the target
git clone https://github.com/RohithV16/aem-code-analyser && cd aem-code-analyser
scripts/run-from-source.sh ../my-aem-project

# 2. As a Maven plugin, once it is installed (see "Install" below)
mvn io.github.rohithv16:aem-analyser-maven-plugin:<version>:check
```

```xml
<!-- 3. Bound to the build, so every `mvn verify` audits the project -->
<plugin>
    <groupId>io.github.rohithv16</groupId>
    <artifactId>aem-analyser-maven-plugin</artifactId>
    <!-- latest version: https://github.com/RohithV16/aem-code-analyser/releases -->
    <version>${aem-analyser.version}</version>
    <executions>
        <execution>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
        </execution>
    </executions>
</plugin>
```

### Common runs

```bash
# Basic run with colored console output (also writes the HTML dashboard)
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project

# Skip the HTML dashboard for a console-only run
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --no-html

# Collect fresh JaCoCo test code coverage dynamically (zero POM changes required)
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --collect-coverage

# Break build on warnings as well as errors
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --fail-on-warning

# Export to SonarQube Generic Issue JSON for CI/CD
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --format SONAR --output target/sonar-reports

# Export to SARIF 2.1.0 for GitHub Code Scanning / Azure DevOps
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --format SARIF --output target/sarif

# Exclude specific OSGi configuration files (reactor-relative globs)
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --ignore-config "ui.config/**/config.publish/**"

# Freeze existing findings, then gate only new ones
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --update-baseline
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --baseline .aem-analyser-baseline.json

# CI-native annotations
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --format GITHUB,GITLAB,AZURE

# Project configuration, history trend and baseline reporting
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --config .aem-analyser.yml
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --format HTML --history target/aem-history
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --baseline .aem-analyser-baseline.json --baseline-report

# Limits
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --max-file-size 10485760 --timeout 300

# Generate rule documentation
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --generate-rule-docs docs/rules

# Discover the rule catalogue
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --list-rules
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --explain AEM-DEAD-002A
```

Accepted findings can also be suppressed inline (Java, HTL or XML comments):

```java
// aem-analyser-disable-next-line AEM-DEAD-001
```
```html
<!-- aem-analyser-disable-next-line AEM-SEC-001 -->
```
```java
// aem-analyser-disable-file AEM-DUP-010
```

> **Tip:** You can create a shell alias in your `~/.zshrc` or `~/.bashrc`:
> ```bash
> alias aem-analyser="java -jar /path/to/aem-code-analyser/aem-analyser-cli/target/aem-analyser-cli.jar"
> ```

---

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
| `--list-rules` | — | Print the rule catalogue and exit. |
| `--explain <RULE>` | — | Print one rule's metadata and exit. |
| `--generate-rule-docs <dir>` | — | Generate Markdown rule pages and exit. |
| `-v, --verbose` | off | Print analysis diagnostics. |
| `--no-color` | off | Disable ANSI colours. |

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
positive case for every rule. It is analyser input rather than a compilable project, and it
exercises the full CLI pipeline rather than isolated unit fixtures.

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
(`*_PARSE_ERROR`). Injection runs relax only the last check, because a real repository may contain
genuinely unparseable sources; those diagnostics are still printed. See
[`validation/probes/README.md`](validation/probes/README.md) for the full probe matrix and how to add
a case.

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

`docs/rules/*.md` are generated from `RuleRegistry`, so they cannot drift from the code:

```bash
scripts/check-rule-docs.sh          # fails when a page no longer matches the code
java -jar <cli.jar> --generate-rule-docs docs/rules   # regenerate
```

---

## Usage Option 2: Maven Plugin in AEM Projects

To automatically enforce dead-code quality checks in any AEM project during `mvn clean verify`, add the plugin to the root `pom.xml` of your target AEM project:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.rohithv16</groupId>
            <artifactId>aem-analyser-maven-plugin</artifactId>
            <!-- latest version: https://github.com/RohithV16/aem-code-analyser/releases -->
            <version>${aem-analyser.version}</version>
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

### Publishing to Maven Central (Sonatype Central)

**Not published to Central yet.** Central requires GPG signatures plus sources and javadoc jars,
which the current release workflow does not produce, so no step of that plan has been executed.
Today's availability:

| Artefact | From | Token |
|---|---|---|
| CLI jar | GitHub Releases | no |
| `aem-analyser-core` | JitPack | no |
| `aem-analyser-maven-plugin` | GitHub Packages | `read:packages` |
| `aem-analyser-maven-plugin` | release assets via `install:install-file` | no |

The full procedure, once that gap is closed, is in the
[Maven Central Publishing Guide](docs/MAVEN_CENTRAL_PUBLISHING_GUIDE.md).
