# AEM Code Analyser

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A static code analysis engine specifically designed for **Adobe Experience Manager (AEM)** projects to detect **cross-boundary dead code, orphaned OSGi configurations, and unreferenced architectural symbols**.

Traditional static analyzers (SonarQube, SpotBugs, PMD) operate in single-language silos and cannot trace connections between Java Declarative Services, OSGi `.cfg.json` configurations, HTL templates, and JCR nodes. AEM Code Analyser builds a unified **Cross-Boundary Reference Graph** to identify dead code across polyglot boundaries.

---

## Features (Phase 1: Java & OSGi Configurations)

* **Orphaned OSGi Configuration Detection (`AEM-DEAD-001`):** Flags configurations in `ui.config` (`.cfg.json`, `.config`) whose PIDs do not match any active `@Component` service, platform PID, or third-party dependency.
* **Dead Configuration Properties (`AEM-DEAD-002A`):** Flags properties declared in `.cfg.json` that are not declared in the bound `@ObjectClassDefinition` interface.
* **Unconsumed OCD Properties (`AEM-DEAD-002B`):** Flags `@AttributeDefinition` methods on configuration interfaces that are never called anywhere in Java.
* **Unused OSGi Services (`AEM-DEAD-003A`):** Identifies OSGi components that are never injected via `@Reference` and do not serve as system entry points (servlets, workflows, filters, schedulers, whiteboard SPIs).
* **Unused Sling Models (`AEM-DEAD-003B`):** Advisory notices for Sling Models without direct Java adaptations.
* **Inactive Delayed Components (`AEM-DEAD-003C`):** Flags components with no service interfaces and `immediate = false`.
* **Duplicate Detection Rules (`AEM-DUP-*`):** Flags duplicate servlet endpoints (`AEM-DUP-001`), duplicate OSGi configurations (`AEM-DUP-002`), duplicate Sling Model bindings (`AEM-DUP-003`), and cloned method code blocks (`AEM-DUP-010`).
* **Adobe Cloud Manager Quality Rules (`AEM-QUAL-*`):**
  * **Path-Bound Servlet Registration (`AEM-QUAL-001` / CQBP-75):** Flags servlets registered by path (`sling.servlet.paths`) instead of resource types.
  * **ProviderType Implementation (`AEM-QUAL-002` / CQBP-84):** Flags custom classes implementing or extending product `@ProviderType` interfaces.
  * **Whiteboard Sling Scheduler Usage (`AEM-QUAL-003` / AMSCORE-554):** Flags whiteboard schedulers, recommending cluster-aware Sling Scheduled Jobs.
  * **Unsafe Component State (`AEM-QUAL-004` / CQBP-ThreadSafety):** Flags non-thread-safe/session fields (`ResourceResolver`, `Session`, etc.) inside OSGi `@Component` singletons.
  * **Deprecated Administrative Access (`AEM-QUAL-005` / AMSCORE-553):** Flags calls to `loginAdministrative()` and `getAdministrativeResourceResolver()`.
* **Multi-Format Export:** Outputs rich ANSI terminal reports, canonical OASIS SARIF 2.1.0, and SonarQube Generic Issue Import JSON.

---

## Getting Started

### Prerequisites
* Java 21+
* Maven 3.9+

### Build & Install Locally
```bash
git clone <repo-url>
cd aem-code-analyser
mvn clean install
```
This builds and installs `aem-analyser-core`, `aem-analyser-maven-plugin`, and `aem-analyser-cli` into your local `~/.m2/repository`.

---

## Usage Option 1: Standalone CLI

Run audits directly from the command line on any AEM repository:

```bash
# Basic run with colored console output
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project

# Collect fresh JaCoCo test code coverage dynamically (zero POM changes required)
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --collect-coverage

# Break build on warnings as well as errors
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --fail-on-warning

# Export to SonarQube Generic Issue JSON for CI/CD
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --format SONAR --output-file target/sonar-generic-issues.json

# Export to SARIF 2.1.0 for GitHub Code Scanning / Azure DevOps
java -jar aem-analyser-cli/target/aem-analyser-cli.jar --project-root /path/to/aem-project --format SARIF --output-file target/aem-analyser.sarif
```

> **Tip:** You can create a shell alias in your `~/.zshrc` or `~/.bashrc`:
> ```bash
> alias aem-analyser="java -jar /path/to/aem-code-analyser/aem-analyser-cli/target/aem-analyser-cli.jar"
> ```

---

## Usage Option 2: Maven Plugin in AEM Projects

To automatically enforce dead-code quality checks in any AEM project during `mvn clean verify`, add the plugin to the root `pom.xml` of your target AEM project:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.rohithv16</groupId>
            <artifactId>aem-analyser-maven-plugin</artifactId>
            <version>1.0.0-SNAPSHOT</version>
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
                <!-- Output format: CONSOLE, SARIF, or SONAR (default: CONSOLE) -->
                <format>CONSOLE</format>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### SonarQube CI/CD Integration
To import the findings into SonarQube without private plugins, configure the plugin to emit SonarQube JSON during your CI build:

```xml
<configuration>
    <format>SONAR</format>
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
For global public distribution without requiring authentication tokens or custom repositories, refer to the step-by-step guide:
[Maven Central Publishing Guide](docs/MAVEN_CENTRAL_PUBLISHING_GUIDE.md).
