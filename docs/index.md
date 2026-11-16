---
layout: default
title: AEM Code Analyser
description: Find dead code, orphaned OSGi configuration, duplication and AEM as a Cloud Service problems across your whole AEM project.
---

# AEM Code Analyser

[![Rules](https://img.shields.io/badge/rules-30-blue.svg)](rules/)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Maven Central](https://img.shields.io/badge/maven%20central-1.0.10-brightgreen.svg)](https://repo1.maven.org/maven2/io/github/rohithv16/)
[![License](https://img.shields.io/badge/license-Apache--2.0-green.svg)](https://github.com/RohithV16/aem-code-analyser/blob/main/LICENSE)

A static analysis tool for Adobe Experience Manager projects. It looks at the whole project at once —
Java, HTL, OSGi configuration, client libraries and content packages — and reports what is easy to
miss one module at a time:

- code that nothing calls any more,
- OSGi configuration that no component owns, and properties that nothing reads,
- duplicated servlet endpoints, conflicting configuration, cloned code,
- patterns that break on AEM as a Cloud Service: plaintext secrets, JSP, `context='unsafe'`,
  deprecated APIs, invalid runmode folders.

You get a console report, an HTML dashboard, and SARIF for GitHub code scanning.

---

## Use it

Three ways, easiest first. None of them needs configuration, a project-root argument or a flag.

### 1 · Run it without installing anything

```bash
git clone https://github.com/RohithV16/aem-code-analyser
cd aem-code-analyser
scripts/run-from-source.sh ~/my-aem-project
```

Builds once, then analyses the path you gave it. Leave the path out to analyse the current directory.

### 2 · Or download the jar

```bash
curl -LO https://github.com/RohithV16/aem-code-analyser/releases/latest/download/aem-analyser-cli.jar
cd ~/my-aem-project && java -jar aem-analyser-cli.jar
```

Self-contained — it needs Java 21 and nothing else. Run it from anywhere inside the project: at the
reactor root it scans the whole reactor, in a module directory it walks up to the reactor that owns it,
in any subdirectory it finds the nearest `pom.xml` above.

### 3 · Or add it to the AEM project's build

This is how a team uses it: one entry in the root `pom.xml`, and every `mvn verify` produces the
report. The plugin comes from Maven Central — no `<pluginRepositories>`, no token.

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.rohithv16</groupId>
            <artifactId>aem-analyser-maven-plugin</artifactId>
            <version>1.0.10</version>
            <!-- once, at the reactor root: module-by-module scans cannot see cross-module findings -->
            <inherited>false</inherited>
            <executions>
                <execution>
                    <phase>verify</phase>
                    <goals><goal>report</goal></goals>
                    <configuration>
                        <formats>SARIF,HTML</formats>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

`mvn verify` then writes `target/aem-analyser/aem-analyser.sarif` and
`aem-analyser-report.html`. The `report` goal never fails the build — switch it to `check` when you
want it to gate pull requests (see [Making it a gate](#making-it-a-gate)).

---

## A worked example

It is wired into a real project here:
**[rohith-aem-portfolio#32](https://github.com/RohithV16/rohith-aem-portfolio/pull/32)** adds the
plugin above to an AEM archetype project (10 modules — `core`, `ui.apps`, `ui.config`, `dispatcher`)
together with a CI job that uploads the SARIF.

On that project it reports **0 errors and 3 warnings**:

| Rule | Where | What it found |
|---|---|---|
| `AEM-QUAL-001` | `core/.../servlets/ResolverServiceTest.java:19` | Servlet registered by path (`/bin/resolverservicetest`) instead of a resource type — Cloud Manager check CQBP-75 |
| `AEM-SEC-001` | `ui.apps/.../xfpage/customfooterlibs.html:25` | `@ context='unsafe'` disables XSS escaping |
| `AEM-SEC-001` | `ui.apps/.../xfpage/customheaderlibs.html:29` | Same expression in the header clientlibs |

Three of the categories worth knowing: a Cloud Service incompatibility, an escaping bypass, and a
cross-module correlation that per-module tools never make.

---

## Reading the results

| Output | Where | Use it for |
|---|---|---|
| Console | your terminal | quick triage while coding |
| HTML dashboard | `target/aem-analyser/aem-analyser-report.html` | sharing with the team: filters, per-finding permalinks, history trend, duplication and coverage panels |
| SARIF | `target/aem-analyser/aem-analyser.sarif` | GitHub code scanning, Azure DevOps |
| SonarQube / GitLab / Azure | `--format SONAR`, `GITLAB`, `AZURE` | the CI you already have |

Each finding names the rule, file and line, the affected symbol, and a remediation sentence. Every
rule is documented in the <a href="rules/">rule catalogue</a>; `--explain AEM-SEC-001` prints the same
explanation in the terminal.

The full set of formats: `CONSOLE` (default), `HTML`, `SARIF`, `SONAR`, `GITHUB`, `GITLAB`, `AZURE`.

### Making it a gate

```xml
<goals><goal>check</goal></goals>
```

`check` fails the build when findings reach the configured threshold. Exit codes: `0` clean,
`1` findings at or above `--fail-on`, `2` the analysis itself failed. From the command line:

```bash
java -jar aem-analyser-cli.jar --fail-on ERROR   # fail on errors only
java -jar aem-analyser-cli.jar --fail-on NONE    # report only, always exits 0
```

---

## Tune it to your project

Three things cover most needs: mute a rule, accept today's findings, silence one line.

```yaml
# .aem-analyser.yml in the project root
rules:
  AEM-DEAD-003B:
    enabled: false
  AEM-SEC-001:
    severity: ERROR
allowlists:
  services:
    - com.example.LegacyService
  configPaths:
    - ui.config/**/config.local/**
```

```bash
# accept everything that exists today, then fail only on new findings
java -jar aem-analyser-cli.jar --update-baseline
java -jar aem-analyser-cli.jar --baseline .aem-analyser-baseline.json
```

```java
// aem-analyser-disable-next-line AEM-DEAD-001
```

```html
<!-- aem-analyser-disable-next-line AEM-SEC-001 -->
```

---

## In CI

{% raw %}
```yaml
- name: Analyse
  run: |
    mvn -B -ntp io.github.rohithv16:aem-analyser-maven-plugin:1.0.10:report \
      -Daem-analyser.formats=SARIF,HTML \
      -Daem-analyser.outputFile=target/aem-analyser

- name: Upload SARIF
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: target/aem-analyser/aem-analyser.sarif
```
{% endraw %}

Calling the goal directly keeps the job fast: it reads sources and configuration without compiling the
project or needing an AEM instance.

For SonarQube:

```bash
java -jar aem-analyser-cli.jar --format SONAR --output target/sonar
sonar-scanner -Dsonar.externalIssuesReportPaths=target/sonar/sonar-aem-analyser-report.json
```

---

## Every rule, in detail

**30 rules in seven families**: dead code (8), duplication (4), Cloud Manager parity (6), performance
(4), AEM as a Cloud Service (5), security (2), component compatibility (1).

**→ <a href="rules/">Browse the rule catalogue</a>** — each page explains what the rule detects, why it
matters on AEM as a Cloud Service, and how to fix it.

| Family | Focus |
|---|---|
| DEAD | Code, configuration and content with no remaining consumer |
| DUP | Duplicate endpoints, configuration, bindings and code blocks |
| QUAL | Cloud Manager quality checks and thread safety |
| PERF | Resource lifecycles and repository query cost |
| CLOUD | Cloud Service compatibility and secrets |
| SEC | Output escaping and request-driven redirects |
| COMP | Deprecated Classic UI dialogs |

Where Adobe Cloud Manager has an equivalent check, the rule id says so (`AEM-QUAL-001` ↔ CQBP-75,
`-002` ↔ CQBP-84, `-003` ↔ AMSCORE-554, `-005` ↔ AMSCORE-553), so existing waivers keep their meaning.

---

## CLI reference

| Flag | Default | Description |
|---|---|---|
| `[project-root]` | current dir | Shorthand for `--project-root`. |
| `-p, --project-root <dir>` | current dir | Project root; omit it to use the current directory (a module directory resolves to its reactor). |
| `-f, --format <list>` | `CONSOLE` | `CONSOLE,SARIF,SONAR,HTML,GITHUB,GITLAB,AZURE`. |
| `--html [path]` / `--no-html` | on | Write, or skip, the HTML dashboard. |
| `-o, --output <dir>` | `target/aem-analyser` | Where reports are written. |
| `--fail-on <severity>` | — | Fail on `ERROR`, `WARNING`, `INFO` or `NONE`. |
| `--fail-on-warning` | off | Alias for `--fail-on WARNING`. |
| `--baseline <file>` | `.aem-analyser-baseline.json` | Accepted findings to ignore. |
| `--update-baseline` | off | Write current findings to the baseline. |
| `--baseline-report` | off | Keep suppressed findings visible at `INFO`. |
| `--config <file>` | `.aem-analyser.yml` | Project configuration file. |
| `--ignore-config <globs>` | — | Configuration paths to exclude. |
| `--ignored-services <classes>` | — | Services that are never "unused". |
| `--dependency-jars <paths>` | — | JARs/directories for PIDs from other bundles. |
| `--changed-only` / `--since <ref>` | off | Only files changed in Git. |
| `--collect-coverage` | off | Run tests with fresh JaCoCo data. |
| `--min-coverage`, `--max-duplication`, `--max-warnings` | — | Quality thresholds. |
| `--fix` / `--fix-dry-run` | off | Apply, or preview, safe automated fixes. |
| `--explain <RULE>` | — | Print one rule's details and exit. |
| `--list-rules` | — | Print the rule catalogue and exit. |
| `--sonar` / `--no-sonar` | on | Embedded SonarJava analysis. |
| `--history <dir>` / `--history-limit <n>` | — / `20` | Keep a run-history trend in the dashboard. |
| `-t, --threads <n>` | cores − 1 | Worker threads. |
| `--profile <list>` | — | Maven profiles to activate during discovery. |
| `--max-file-size <bytes>` | `10485760` | Skip larger files. |
| `--timeout <seconds>` | `0` | Overall time budget (0 = none). |
| `-v, --verbose` | off | Print analysis diagnostics. |
| `--no-color` | off | Disable ANSI colours. |

---

## Documentation

- <a href="rules/">Rule catalogue</a> — all 30 rules, detection logic and remediation
- [Releases](https://github.com/RohithV16/aem-code-analyser/releases) — CLI jar, checksums, Maven artefacts
- [Maven Central](https://repo1.maven.org/maven2/io/github/rohithv16/) — plugin and library coordinates
- [README](https://github.com/RohithV16/aem-code-analyser#readme) · [Changelog](https://github.com/RohithV16/aem-code-analyser/blob/main/CHANGELOG.md) ·
  [Contributing](https://github.com/RohithV16/aem-code-analyser/blob/main/CONTRIBUTING.md) · [Security policy](https://github.com/RohithV16/aem-code-analyser/blob/main/SECURITY.md)
- Maintainers: [release and Maven Central guide](MAVEN_CENTRAL_PUBLISHING_GUIDE.html)
