# AEM Code Analyser — Architecture

## 1. Overview & Purpose

The **AEM Code Analyser** is an enterprise-grade static analysis tool purpose-built for Adobe Experience Manager (AEM as a Cloud Service and AEM 6.5) applications. Its core objective is to detect **cross-boundary dead code, orphaned configurations, and unreferenced architectural symbols** across polyglot boundaries.

Traditional static analysis tools (SonarQube, SpotBugs, PMD, Checkstyle) operate within single-language silos. In an AEM application, Java classes, Declarative Services (DS) annotations, OSGi metatype configurations (`.cfg.json`, `.config`), HTL templates, Clientlibs, and JCR XML definitions are coupled via Inversion of Control (IoC), runtime reflection, and naming conventions. Because traditional analyzers cannot trace references across these polyglot boundaries, they either report massive false positives or suppress dead code checks entirely on `@Component` and `@Model` classes, leaving enterprise repositories vulnerable to silent configuration failures, ghost properties, and zombie services.

What it covers today:

- **30 rule ids across seven families** — DEAD (8), DUP (4), QUAL (6), PERF (4), CLOUD (5), SEC (2),
  COMP (1). `AEM-QUAL-006` is retained for baseline compatibility but retired as a producer;
  `AEM-DUP-010` is emitted by the embedded PMD CPD scan.
- **Cross-boundary reference graph** over Java ASTs, OSGi metatypes, HTL, client libraries, JCR
  content and dependency JARs.
- **Multi-module discovery** for arbitrary Maven layouts, with no hardcoded paths.
- **Seven report formats** — CONSOLE, HTML, SARIF 2.1.0, SonarQube Generic Issues, GitHub, GitLab and
  Azure annotations.
- **Safe remediation** — read-only by default; `--fix` repairs only proven cases, `--fix-dry-run`
  previews them.

---

## 2. System Architecture

The AEM Code Analyser employs a **Decoupled Tri-Tier Architecture** centered around a unified in-memory Cross-Boundary Reference Graph and a **Two-Phase Thread-Confined Ingestion Pipeline**:

```
                          ┌────────────────────────────────────────────────────────┐
                          │                   Delivery Formats                     │
                          │   SARIF 2.1.0  │  Sonar Generic JSON  │  ANSI Console  │
                          └───────────────────────────▲────────────────────────────┘
                                                      │
                         ┌────────────────────────────┴────────────────────────────┐
                         │                    Delivery Interfaces                  │
                         │   aem-analyser-maven-plugin   │    aem-analyser-cli     │
                         └────────────────────────────▲────────────────────────────┘
                                                      │
┌─────────────────────────────────────────────────────┴──────────────────────────────────────────────────────┐
│                                             aem-analyser-core                                              │
│                                                                                                            │
│  ┌────────────────────────┐    ┌────────────────────────┐    ┌──────────────────────────────────────────┐  │
│  │ Multi-Module Discovery │    │ Parallel AST Parsers   │    │ Thread-Confined Reference Graph          │  │
│  │ - 11-Category Taxonomy │    │ (Worker Threads 1..N)  │    │ (Dedicated Ingestion Thread)             │  │
│  │ - Container Disambig.  │    │ - JavaParser (Java DS) │───►│ - JGraphT Directed Multigraph            │  │
│  │ - Strict osgiconfig/   │    │ - Jackson RFC 724 JSON │    │ - CONSUMES_PROPERTY & Cross-Edges        │  │
│  │ - Cloud Config Engine  │    │ - Felix Handler (.conf)│    │ - Secondary Concurrent Hash Indices      │  │
│  │ - 3-Layer Tooling Gate │    │ - ASM Bytecode Indexer │    │ - Metatype 1.4 Lookahead Normalizer      │  │
│  └───────────┬────────────┘    └───────────┬────────────┘    └────────────────────┬─────────────────────┘  │
│              │                             │ (Immediate AST Free)                 │                        │
│              └─────────────────────────────┼──────────────────────────────────────┘                        │
│                                            ▼                                                               │
│                              ┌───────────────────────────┐                                                 │
│                              │  Rule Evaluation Engine   │                                                 │
│                              │  - AEM-DEAD-001 (DS 1.4)  │                                                 │
│                              │  - AEM-DEAD-002A / 002B   │                                                 │
│                              │  - AEM-DEAD-003A / 003B/C │                                                 │
│                              │  - 11-Category SPI Catalog│                                                 │
│                              │  - Framework Whitelists   │                                                 │
│                              └───────────────────────────┘                                                 │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Core Architectural Tiers
1. **`aem-analyser-core`**:
   - Headless analysis engine containing the domain model, AST visitors, parser abstractions, graph data structures, rule evaluators, and reporting exporters.
   - Implements a **Two-Phase Thread-Confined Ingestion Pipeline**: Worker threads parse Java and configuration files in parallel, immediately extracting symbol definitions and `CONSUMES_PROPERTY` pending edges into intermediate DTOs and freeing JavaParser `CompilationUnit` ASTs from memory. A single dedicated ingestion thread populates JGraphT's `DirectedPseudograph` and secondary indices without locks or thread contention, slashing memory usage from $3+\text{ GB}$ to $<50\text{ MB}$ and completing ingestion in under $250\text{ ms}$.
   - Zero dependency on Maven runtime or CLI frameworks; can be embedded in any JVM tool.
2. **`aem-analyser-maven-plugin`**:
   - Native Apache Maven plugin providing goals `aem-analyser:check` (build-breaking quality gate during `verify` or `process-classes`) and `aem-analyser:report` (report generation).
   - Automatically binds to the Maven reactor, leverages Maven project model dependencies, and enables zero-configuration integration in CI/CD pipelines (Adobe Cloud Manager, Jenkins, GitHub Actions).
3. **`aem-analyser-cli`**:
   - Standalone command-line executable powered by **Picocli**, shaded into one runnable jar (`aem-analyser-cli.jar`, ~34 MB with SonarJava, PMD CPD and JDT embedded).
   - Designed for sub-second developer inner-loop scans, pre-commit hooks (Husky, lint-staged), and standalone audits of unbuilt repositories.

---

## 3. How a run works

1. **Resolve the root** — `--project-root`, the positional argument, or the working directory. When
   the start directory sits inside a module (a POM whose declared parent exists on disk), discovery
   walks up to the reactor, because a module-only scan cannot see cross-module findings. An empty
   `<relativePath/>` stops the walk: that parent comes from a repository.
2. **Discover modules** — recursive POM walk, profile evaluation, 11-category classification,
   `<embeddeds>` container disambiguation and a strict `osgiconfig/` requirement. Tooling noise
   (`node_modules`, `target`, `dist`) is filtered from every walk.
3. **Parse in parallel** — JavaParser for DS components, OCDs, Sling Models and call sites; Jackson
   for `.cfg.json` with RFC 724 typed keys (`:Integer`, `:String[]`); the Felix handler for `.config`;
   ASM for dependency JARs; dedicated scanners for HTL, client libraries and JCR content.
4. **Build the graph** — typed nodes and edges (including `CONSUMES_PROPERTY`) plus indices for
   components by FQN, models by resource type, OSGi configuration by PID and runmode.
5. **Evaluate rules** — `RuleCatalog`, validated against `RuleId` at startup, runs the registered
   evaluators; the embedded PMD CPD and SonarJava scans contribute their findings.
6. **Deliver** — exporters write the requested formats, the HTML dashboard and the run history;
   inline suppression, baselines and `--fail-on` decide the exit code.

### 3.1 Capabilities

| Area | Covered by |
|---|---|
| Dead code | `AEM-DEAD-001` orphaned configs, `-002A` ghost properties, `-002B` unconsumed OCD attributes, `-003A` unused services, `-003B` unused Sling Models, `-003C` inactive delayed components, `-004` broken HTL references, `-005` orphaned client libraries |
| Duplication | `AEM-DUP-001` endpoints, `-002` OSGi configs, `-003` Sling Models, `-010` cloned code (PMD CPD) |
| Cloud Manager parity | `AEM-QUAL-001` CQBP-75, `-002` CQBP-84, `-003` AMSCORE-554, `-004` thread safety, `-005` AMSCORE-553 |
| Performance | `AEM-PERF-001`/`-002` unclosed resolvers and sessions, `-003` unbounded traversal, `-004` unlimited queries |
| Cloud Service | `AEM-CLOUD-001` secrets, `-002` JSP, `-003` service users, `-004` deprecated APIs, `-005` runmode folders |
| Security | `AEM-SEC-001` `context='unsafe'`, `AEM-SEC-002` redirect/SSRF |
| Components | `AEM-COMP-001` Classic UI dialogs |

---

## 5. Interface Contracts & Core Domain Models

### 5.1 Polyglot Node Base Contract
```java
package io.github.rohithv16.aem.analyser.core.model.graph;

import java.nio.file.Path;

public interface PolyglotNode {
    String getId();
    NodeType getNodeType();
    Path getFilePath();
    SourceLocation getSourceLocation();
}
```

### 5.2 Directed Polyglot Edge Base Contract & Taxonomy
```java
package io.github.rohithv16.aem.analyser.core.model.graph;

public interface PolyglotEdge {
    EdgeType getEdgeType();
    PolyglotNode getSource();
    PolyglotNode getTarget();
    String getDescription();
}

public enum EdgeType {
    CONFIGURES,          // OSGiConfigNode -> OSGiComponentNode
    DESIGNATES_OCD,      // OSGiComponentNode -> OSGiOcdNode
    DEFINES_PROPERTY,    // OSGiOcdNode -> OcdPropertyMethodNode
    BINDS_PROPERTY,      // ConfigPropertyNode -> OcdPropertyMethodNode
    CONTAINS_PROPERTY,   // OSGiConfigNode -> ConfigPropertyNode
    CONSUMES_PROPERTY,   // OSGiComponentNode -> OcdPropertyMethodNode (Direct call/field)
    INJECTS_SERVICE,     // OSGiComponentNode | SlingModelNode -> OSGiComponentNode
    ADAPTS_TO,           // JavaClassNode -> SlingModelNode
    REFERENCES_CLASS     // JavaClassNode -> JavaClassNode
}
```

### 5.3 Specialized Property Consumption Edge
```java
package io.github.rohithv16.aem.analyser.core.model.graph.edge;

import io.github.rohithv16.aem.analyser.core.model.graph.*;
import io.github.rohithv16.aem.analyser.core.model.graph.node.*;

public record ConsumesPropertyEdge(
    OSGiComponentNode sourceComponent,
    OcdPropertyMethodNode targetPropertyMethod,
    SourceLocation callSiteLocation,
    InvocationType invocationType,
    String enclosingMethodName
) implements PolyglotEdge {

    public enum InvocationType {
        DIRECT_METHOD_CALL,       // config.endpointUrl()
        FIELD_MEMBER_ACCESS,      // this.config.endpointUrl()
        CONSTRUCTOR_INJECTION,    // Constructor parameter call site
        COMPONENT_PROPERTY_TYPE   // OSGi R7 @ComponentPropertyType annotation
    }

    @Override public EdgeType getEdgeType() { return EdgeType.CONSUMES_PROPERTY; }
    @Override public PolyglotNode getSource() { return sourceComponent; }
    @Override public PolyglotNode getTarget() { return targetPropertyMethod; }
    @Override public String getDescription() {
        return "Component " + sourceComponent.getId() + " consumes property method " +
               targetPropertyMethod.getId() + " at " + callSiteLocation;
    }
}
```

### 5.4 Multi-Module Classification Taxonomy
```java
package io.github.rohithv16.aem.analyser.core.model.discovery;

public enum ModuleClassification {
    AGGREGATOR_MODULE,        // Maven aggregator POM containing child <modules>
    CONTAINER_MODULE,         // Deployment container ('all') embedding packages via <embeddeds>
    CONFIG_MODULE,            // OSGi configuration content package containing 'osgiconfig/'
    BUNDLE_MODULE,            // OSGi bundle (Java source, Declarative Services, Metatypes)
    APPS_MODULE,              // Application content package ('ui.apps') with components and HTL
    CONTENT_MODULE,           // Mutable content package ('ui.content') with JCR repo content
    STRUCTURE_MODULE,         // Repository structure package ('ui.apps.structure') defining root filters
    ACL_MODULE,               // Access control content package ('ui.acl') using AC Tool or JCR permissions
    FRONTEND_MODULE,          // Frontend build module ('ui.frontend') managing npm/Webpack/Vite assets
    DISPATCHER_MODULE,        // HTTP and Dispatcher configuration module packaging vhosts/filters
    TEST_MODULE,              // Integration and E2E test module ('it.tests', Cypress test suites)
    PIPELINE_MODULE,          // CI/CD pipeline definition module ('pipelines')
    CLOUD_CONFIG_DESCRIPTOR,  // Non-Maven Cloud Manager descriptor (root config/{env}/cdn.yaml, WAF)
    UNCLASSIFIED              // Fallback for unrecognized modules
}
```

### 5.5 Universal Coordinates & Issue Location Model
```java
package io.github.rohithv16.aem.analyser.core.model.graph;

import java.nio.file.Path;

public record SourceLocation(Path projectRelativePath, int line, int column) {}

public record TextRegion(int startLine, int startColumn, int endLine, int endColumn) {
    public static TextRegion forWholeFile() {
        return new TextRegion(1, 1, 1, 2);
    }
}
```

### 5.6 Core Analysis Engine Contract
```java
package io.github.rohithv16.aem.analyser.core.api;

public interface AemCodeAnalyser {
    AnalysisReport analyze(AnalysisContext context) throws AnalysisException;
}
```

### 5.7 Report Exporter Contract
```java
package io.github.rohithv16.aem.analyser.core.export;

import io.github.rohithv16.aem.analyser.core.model.report.AnalysisReport;
import java.io.Writer;

public interface ReportExporter {
    ReportFormat getFormat();
    void export(AnalysisReport report, Writer writer) throws ExportException;
}
```

---

## 6. Code Layout & Repository Structure

```
aem-code-analyser/
├── PROJECT.md                        # Architecture overview (this file)
├── README.md                         # User guide: install, CLI, Maven plugin, CI
├── CHANGELOG.md                      # Shipped changes, newest first
├── CONTRIBUTING.md                   # How to add a rule, run the gates
├── pom.xml                           # Root parent POM (Java 21, enforcer >= 3.9)
├── docs/                             # Published site (GitHub Pages, Jekyll layout + one stylesheet)
│   ├── index.html                    # Overview, quick start, install paths
│   ├── getting-started/              # Install, verify, gate, CI, tune, troubleshooting
│   ├── issues/                       # Generated issue reference: every rule, why, fix, examples
│   ├── reference/                    # Every flag, format, exit code and config key
│   ├── demo/                         # Committed sample report (scripts/build-demo-report.sh)
│   ├── maintainers/                  # Release and Maven Central guide
│   └── assets/                       # site.css, site.js, favicon
├── scripts/
│   ├── next-version.py               # Snapshot-aware release/snapshot arithmetic
│   ├── run-from-source.sh            # Run the CLI from a clone, no jar download
│   ├── run-rule-probes.sh            # Probe gate: every active rule must fire
│   ├── run-reactor-validation.sh     # Golden findings for validation/reactor
│   ├── check-rule-docs.sh            # docs/issues must equal generated output
│   ├── check_rule_probes.py          # Probe-result checker
│   └── inject-rule-probes.sh         # Run probes inside a temp copy of a repo
├── validation/
│   ├── probes/                       # One deliberate fixture per rule
│   └── reactor/                      # Three-module project + pinned findings
├── aem-analyser-core/                # Analysis engine
│   ├── src/main/java/io/github/rohithv16/aem/analyser/core/
│   │   ├── api/                      # Public entry points (DefaultAemCodeAnalyser)
│   │   ├── discovery/                # Reactor discovery, module classification
│   │   ├── parser/                   # Java AST, OSGi config, bytecode (ASM) indexers
│   │   ├── model/                    # Immutable domain model (nodes, edges, reports)
│   │   ├── graph/                    # Reference graph and secondary indices
│   │   ├── rules/                    # RuleEvaluator implementations
│   │   │   ├── RuleCatalog.java      # Registry, validated against RuleId at startup
│   │   │   ├── catalogs/             # Platform PIDs, system entry points
│   │   │   └── impl/                 # One class per rule id
│   │   ├── baseline/                 # Fingerprints, inline suppression
│   │   ├── export/                   # SARIF, Sonar, GitLab, Azure, GitHub, console, HTML
│   │   └── fix/                      # Safe automated fixes (--fix)
│   └── src/test/java/…               # Unit and golden-file tests
├── aem-analyser-maven-plugin/        # check / report goals, Invoker ITs (src/it)
├── aem-analyser-cli/                 # Picocli CLI, shaded into a single runnable jar
└── .github/workflows/                # ci.yml (verify/ITs/validation/quality + release)
                                      # publish-central.yml (manual Maven Central only)
```

---

## 7. Verification Strategy

1. **Unit and golden-file tests** — JavaParser visitors, config parsers, ASM indexer, every rule
   implementation, exporters (including SARIF schema conformance).
2. **Rule probe gate** — `validation/probes` holds a deliberate positive fixture for every active
   rule; `scripts/run-rule-probes.sh` fails if a rule does not fire, fires only outside probe code, or
   has no probe, and if the parser silently skipped a file.
3. **Cross-module reactor** — `validation/reactor` pins the findings of a realistic three-module
   project, covering cases single-file probes cannot express: wildcard-import injection, inherited
   `@Reference`, PIDs built from constants in another class, factory PID amendments and cross-module
   clientlib references.
4. **Documentation gate** — `docs/issues/index.html` must equal `--generate-rule-docs` output.
5. **Cross-platform CI** — the full suite runs on Linux, macOS and Windows; path handling that only
   breaks on Windows has shipped before, so the matrix is part of the contract.
6. **Consumer proof** — every documented consumption route (release jar, JitPack library, plugin via
   GitHub Packages, offline `install:install-file`) is executed end to end against a real project.
