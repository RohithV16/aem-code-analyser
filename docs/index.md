---
layout: default
title: AEM Code Analyser
---

# AEM Code Analyser

Cross-boundary dead-code, duplication and AEM as a Cloud Service analysis for Adobe Experience Manager
projects.

[View the project on GitHub](https://github.com/rohithv16/aem-code-analyser)

## Quick start

### CLI

```bash
mvn -q -DskipTests package

java -jar aem-analyser-cli/target/aem-analyser-cli.jar \
  --project-root /path/to/aem-project
```

Every run writes a console report **and** an HTML dashboard
(`target/aem-analyser/aem-analyser-report.html`) unless `--no-html` is passed.

### Maven

```xml
<plugin>
  <groupId>io.github.rohithv16</groupId>
  <artifactId>aem-analyser-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <executions>
    <execution>
      <phase>verify</phase>
      <goals><goal>check</goal></goals>
    </execution>
  </executions>
</plugin>
```

## Rule catalogue

| Rule | Severity | Summary |
|---|---|---|
| [AEM-CLOUD-001](rules/AEM-CLOUD-001.html) | ERROR | HardcodedCloudSecret |
| [AEM-CLOUD-002](rules/AEM-CLOUD-002.html) | WARNING | DeprecatedJspUsage |
| [AEM-CLOUD-003](rules/AEM-CLOUD-003.html) | ERROR | UnmappedSubserviceUser |
| [AEM-CLOUD-004](rules/AEM-CLOUD-004.html) | WARNING | DeprecatedAemApiUsage |
| [AEM-CLOUD-005](rules/AEM-CLOUD-005.html) | WARNING | InvalidRunmodeFolder |
| [AEM-COMP-001](rules/AEM-COMP-001.html) | WARNING | DeprecatedClassicUiDialog |
| [AEM-DEAD-001](rules/AEM-DEAD-001.html) | ERROR | OrphanedOsgiConfiguration |
| [AEM-DEAD-002A](rules/AEM-DEAD-002A.html) | WARNING | GhostConfigurationProperty |
| [AEM-DEAD-002B](rules/AEM-DEAD-002B.html) | WARNING | UnconsumedOcdProperty |
| [AEM-DEAD-003A](rules/AEM-DEAD-003A.html) | WARNING | UnusedOsgiService |
| [AEM-DEAD-003B](rules/AEM-DEAD-003B.html) | INFO | UnusedSlingModel |
| [AEM-DEAD-003C](rules/AEM-DEAD-003C.html) | WARNING | InactiveDelayedComponent |
| [AEM-DEAD-004](rules/AEM-DEAD-004.html) | ERROR | BrokenHtlReference |
| [AEM-DEAD-005](rules/AEM-DEAD-005.html) | INFO | OrphanedClientlibCategory |
| [AEM-DUP-001](rules/AEM-DUP-001.html) | ERROR | DuplicateServletEndpoint |
| [AEM-DUP-002](rules/AEM-DUP-002.html) | WARNING | DuplicateOsgiConfiguration |
| [AEM-DUP-003](rules/AEM-DUP-003.html) | WARNING | DuplicateSlingModel |
| [AEM-DUP-010](rules/AEM-DUP-010.html) | WARNING | DuplicateCodeBlock |
| [AEM-PERF-001](rules/AEM-PERF-001.html) | ERROR | UnclosedResourceResolver |
| [AEM-PERF-002](rules/AEM-PERF-002.html) | ERROR | UnclosedJcrSession |
| [AEM-PERF-003](rules/AEM-PERF-003.html) | WARNING | OakQueryTraversalRisk |
| [AEM-PERF-004](rules/AEM-PERF-004.html) | WARNING | UnboundedQuery |
| [AEM-QUAL-001](rules/AEM-QUAL-001.html) | WARNING | PathBoundServletRegistration |
| [AEM-QUAL-002](rules/AEM-QUAL-002.html) | ERROR | ProviderTypeImplementation |
| [AEM-QUAL-003](rules/AEM-QUAL-003.html) | WARNING | SlingSchedulerUsage |
| [AEM-QUAL-004](rules/AEM-QUAL-004.html) | ERROR | UnsafeComponentState |
| [AEM-QUAL-005](rules/AEM-QUAL-005.html) | ERROR | AdministrativeAccessUsage |
| [AEM-QUAL-006](rules/AEM-QUAL-006.html) | WARNING | ResolverStoredInComponentField |
| [AEM-SEC-001](rules/AEM-SEC-001.html) | ERROR | UnsafeHtlContextBypass |
| [AEM-SEC-002](rules/AEM-SEC-002.html) | WARNING | OpenRedirectOrSsrf |

## Reports

- **CONSOLE** — severity-ordered findings with remediation.
- **SARIF 2.1.0** — GitHub Code Scanning, Azure DevOps.
- **SonarQube Generic Issues** — `sonar.externalIssuesReportPaths`.
- **HTML dashboard** — findings, duplicate blocks with source previews, scan metrics, coverage,
  duplication breakdown and run-history trend.
- **GITHUB / GITLAB / AZURE** — native CI annotations.

## Configuration

- `.aem-analyser.yml` — enable/disable rules, severity overrides, allowlists.
- `.aem-analyser-baseline.json` — freeze accepted findings (`--update-baseline`).
- Inline suppression — `// aem-analyser-disable-next-line AEM-DEAD-001`.

## Documentation

- [Application specification](AEM_CODE_ANALYSER_SPECIFICATION.html)
- [Maven Central publishing guide](MAVEN_CENTRAL_PUBLISHING_GUIDE.html)
- [Full CLI and Maven reference](https://github.com/rohithv16/aem-code-analyser/blob/main/README.md#cli-options-reference)
- [Individual rule pages](rules/)
- [Contributing](https://github.com/rohithv16/aem-code-analyser/blob/main/CONTRIBUTING.md)
- [Security policy](https://github.com/rohithv16/aem-code-analyser/blob/main/SECURITY.md)
