# AEM Code Analyser (Phase 1: Java & OSGi Configurations)

## 1. Overview & Purpose

The **AEM Code Analyser** is an enterprise-grade static analysis tool purpose-built for Adobe Experience Manager (AEM as a Cloud Service and AEM 6.5) applications. Its core objective is to detect **cross-boundary dead code, orphaned configurations, and unreferenced architectural symbols** across polyglot boundaries.

Traditional static analysis tools (SonarQube, SpotBugs, PMD, Checkstyle) operate within single-language silos. In an AEM application, Java classes, Declarative Services (DS) annotations, OSGi metatype configurations (`.cfg.json`, `.config`), HTL templates, Clientlibs, and JCR XML definitions are coupled via Inversion of Control (IoC), runtime reflection, and naming conventions. Because traditional analyzers cannot trace references across these polyglot boundaries, they either report massive false positives or suppress dead code checks entirely on `@Component` and `@Model` classes, leaving enterprise repositories vulnerable to silent configuration failures, ghost properties, and zombie services.

**Phase 1** focuses strictly on the foundational boundary between **Java code and OSGi configurations**:
- Detection of orphaned OSGi configurations (`.cfg.json`, `.config`) in `ui.config` modules matching Java services or platform PIDs.
- Detection of dead, ghost, or unreferenced OSGi configuration properties.
- Detection of unused Sling Models, OSGi services, and Declarative Services components across bundle boundaries.
- Cross-boundary reference graph construction using high-performance AST parsing and symbol indexing.
- Multi-module project discovery supporting arbitrary Maven layouts without hardcoded paths.
- Publication of findings in standard CI/CD formats: SARIF 2.1.0, SonarQube Generic Issue Import JSON, and rich ANSI console diagnostics.

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
   - Standalone command-line executable powered by **Picocli**, packaged as a fat runnable JAR (and optional GraalVM native-image binary).
   - Designed for sub-second developer inner-loop scans, pre-commit hooks (Husky, lint-staged), and standalone audits of unbuilt repositories.

---

## 3. Feature Inventory & Phase 1 Milestone Assignments

| Feature ID | Feature Name | Target Module | Phase 1 Milestone | Description | Status |
|---|---|---|---|---|---|
| **FEAT-DISC-01** | Recursive POM Tree Walker | `core` | **M1** | Traverses nested Maven POM hierarchies across arbitrary depth, evaluating active profiles without hardcoded paths. | Planned |
| **FEAT-DISC-02** | Semantic Module Classifier | `core` | **M1** | Classifies modules using 11-type taxonomy (`AGGREGATOR`, `CONTAINER`, `CONFIG`, `BUNDLE`, `APPS`, `CONTENT`, `STRUCTURE`, `ACL`, `FRONTEND`, `DISPATCHER`, `TEST`, `PIPELINE`), with `<embeddeds>` container disambiguation and strict `osgiconfig/` path requirement. | Planned |
| **FEAT-DISC-03** | Cloud Configuration Discovery Engine | `core` | **M1** | Scans repository root non-Maven configurations (`config/{dev,stage,prod,rde}/cdn.yaml`, WAF, Rate Limiting) and links environment tiers. | Planned |
| **FEAT-DISC-04** | Tooling File Hazard Eliminator | `core` | **M1** | 3-layer defense-in-depth scanner excluding `lombok.config`, `.editorconfig`, and requiring OSGi PID grammar validation. | Planned |
| **FEAT-PARSE-01** | Java Declarative Services AST Visitor | `core` | **M2** | JavaParser visitor extracting `@Component`, `@Reference`, `@Designate`, service interfaces, and properties. | Planned |
| **FEAT-PARSE-02** | OSGi Metatype OCD AST Visitor | `core` | **M2** | JavaParser visitor extracting `@ObjectClassDefinition` and `@AttributeDefinition` methods, explicit `id`, defaults, and return types. | Planned |
| **FEAT-PARSE-03** | Sling Model AST Visitor | `core` | **M2** | JavaParser visitor extracting `@Model` adaptables, adapters, and `resourceType` bindings. | Planned |
| **FEAT-PARSE-04** | OSGi `.cfg.json` Parser | `core` | **M2** | Jackson streaming parser with exact line/column tracking, RFC 724 typed key stripping (`:Integer`, `:String[]`), comment support, and env-var placeholders. | Planned |
| **FEAT-PARSE-05** | Legacy Apache Felix `.config` Parser | `core` | **M2** | Robust parser for Felix typed property syntax, escaped characters, and hyphen/tilde factory delimiters. | Planned |
| **FEAT-PARSE-06** | Dependency Bytecode Indexer | `core` | **M2** | High-speed ASM scanner extracting exported PIDs and service interfaces from external dependency JARs. | Planned |
| **FEAT-GRAPH-01** | Thread-Confined Reference Graph Engine | `core` | **M3** | JGraphT directed multigraph linking configs, properties, components, OCDs, models, and `CONSUMES_PROPERTY` edges with $<50\text{ MB}$ heap footprint. | Planned |
| **FEAT-GRAPH-02** | Secondary In-Memory Lookups | `core` | **M3** | Concurrent hash maps indexing nodes by PID, interface, resource type, OCD FQN, and runmode for $O(1)$ lookups. | Planned |
| **FEAT-GRAPH-03** | Metatype 1.4 (§105.10) Lookahead Normalizer | `core` | **M3** | Single-pass lookahead normalizer with leading underscore removal (`_default` $\rightarrow$ `default`) and `@AttributeDefinition(id)` override. | Planned |
| **FEAT-RULE-01** | Rule AEM-DEAD-001 (Orphaned Configurations) | `core` | **M4** | Detects orphaned configs via DS 1.4 §112.8.2 `ActivePids(K)` with default PID suppression and `"$"` expansion, emitting `ORPHANED_OCD_NAMED_CONFIG`. | Planned |
| **FEAT-RULE-02** | Platform PID Catalog & Whitelist | `core` | **M4** | Curated catalog of standard platform PIDs and comprehensive framework whitelist (Sling servlets/filters, schedulers, event topics, health checks). | Planned |
| **FEAT-RULE-03** | Rule AEM-DEAD-002A (Ghost Configuration Properties) | `core` | **M4** | Detects config properties not declared in bound `@ObjectClassDefinition` using RFC 724 typed key stripping. | Planned |
| **FEAT-RULE-04** | Rule AEM-DEAD-002B (Unconsumed OCD Properties) | `core` | **M4** | Detects `@AttributeDefinition` methods never accessed, aggregating call sites across all designating components via `CONSUMES_PROPERTY` in-degree. | Planned |
| **FEAT-RULE-05** | Rule AEM-DEAD-003A / 003C (Unused Services) | `core` | **M4** | Detects unused OSGi services evaluating service interface package exports and tightened `immediate = true` semantics; flags inactive delayed components. | Planned |
| **FEAT-RULE-06** | System Entry Point Catalog | `core` | **M4** | 11-category catalog exempting Servlets, Filters, EventHandlers, Schedulers, Workflows, and Whiteboard SPIs (Injectors, Exporters, AuthHandlers). | Planned |
| **FEAT-RULE-07** | Rule AEM-DEAD-003B (Unused Sling Models) | `core` | **M4** | Detects Sling Models with 0 Java adaptations, unbound `resourceType`, and not exported. | Complete |
| **FEAT-RULE-08** | Rule AEM-QUAL-001 (Path-Bound Servlets / CQBP-75) | `core` | **M4** | Detects servlets registered via `sling.servlet.paths` instead of resource types. | Complete |
| **FEAT-RULE-09** | Rule AEM-QUAL-002 (ProviderType Implementation / CQBP-84) | `core` | **M4** | Detects custom classes implementing/extending `@ProviderType` product interfaces. | Complete |
| **FEAT-RULE-10** | Rule AEM-QUAL-003 (Sling Scheduler Usage / AMSCORE-554) | `core` | **M4** | Detects whiteboard Sling Schedulers; enforces Sling Scheduled Jobs for cluster reliability. | Complete |
| **FEAT-RULE-11** | Rule AEM-QUAL-004 (Unsafe Component State / CQBP-ThreadSafety) | `core` | **M4** | Detects session/request fields (`ResourceResolver`, `Session`) in `@Component` singletons. | Complete |
| **FEAT-RULE-12** | Rule AEM-QUAL-005 (Administrative Logins / AMSCORE-553) | `core` | **M4** | Detects deprecated `loginAdministrative()` and `getAdministrativeResourceResolver()`. | Complete |
| **FEAT-DUP-01** | Rules AEM-DUP-001/002/003/010 (Duplication Engine) | `core` | **M4** | Detects duplicate endpoints, duplicate configs, duplicate Sling Models, and code clones. | Complete |
| **FEAT-DELIV-01** | Maven Plugin (`aem-analyser-maven-plugin`) | `maven-plugin` | **M5** | Maven Mojo bindings for `check` and `report`, lifecycle hooks, and build-fail thresholds. | Planned |
| **FEAT-DELIV-02** | Standalone CLI (`aem-analyser-cli`) | `cli` | **M5** | Picocli CLI executable, parameter handling (`--project-root`, `--fail-on-warning`, `--format`). | Planned |
| **FEAT-REP-01** | SARIF 2.1.0 Exporter | `core` | **M5** | Canonical OASIS SARIF 2.1.0 exporter with `ruleIndex`, `%SRCROOT%`, and 1-based coordinates for GitHub Code Scanning / Azure DevOps. | Planned |
| **FEAT-REP-02** | SonarQube Generic Issue Import JSON Exporter | `core` | **M5** | Native SonarQube JSON exporter with reactor-root relative paths and 0-based coordinate transformation ($C_{\text{sonar}} = C_{\text{jp}} - 1$). | Planned |
| **FEAT-REP-03** | ANSI Console Formatter | `core` | **M5** | Colored, formatted terminal diagnostics with line numbers, code snippets, and remediation steps. | Planned |

---

## 4. Phased Milestones Breakdown

### Milestone 1 (M1): Foundation & Generic Multi-Module Discovery Engine
- **Objective**: Establish project foundation and implement generic, recursive Maven reactor discovery capable of analyzing arbitrary multi-module layouts without hardcoded paths.
- **Key Deliverables**: `pom.xml`, `aem-analyser-core` module setup, `ProjectDiscoveryEngine`, `ModuleDescriptor`, 11-category module classifier with `<embeddeds>` container package disambiguation, non-Maven `CloudConfigDiscoveryEngine`, 3-layer tooling exclusion (`lombok.config`), multi-tenant test fixtures modeled after `aemaacs-life` and `rohith-aem-portfolio`.
- **Success Criteria**: 100% accurate classification across all 32 modules in reference repositories (0.0% unclassified rate); complete isolation from non-OSGi tooling configurations.

### Milestone 2 (M2): Polyglot Parsing & AST Symbol Extraction Engine
- **Objective**: Implement standalone source parsers for Java (Declarative Services, Metatype OCDs, Sling Models) and OSGi configurations (`.cfg.json`, `.config`).
- **Key Deliverables**: JavaParser AST visitors (`OsgiComponentVisitor`, `MetatypeOcdVisitor` with `@AttributeDefinition(id)` support, `SlingModelVisitor`), `ComponentPropertyConsumptionVisitor` extracting `CONSUMES_PROPERTY` references on-the-fly and immediately dereferencing ASTs, Jackson line-tracking `.cfg.json` parser with RFC 724 typed key stripping (`:Integer`, `:String[]`), Felix `.config` parser, ASM dependency indexer.
- **Success Criteria**: Flawless extraction of PIDs, properties, references, and metatypes with exact line/column numbers; resident heap memory strictly bounded under $<50\text{ MB}$.

### Milestone 3 (M3): Cross-Boundary Reference Graph & In-Memory Indexing
- **Objective**: Construct the directed multigraph linking decoupled Java symbols to configuration files and properties using a thread-confined ingestion pipeline.
- **Key Deliverables**: JGraphT `DirectedPseudograph` graph implementation, typed nodes and edges (including `CONSUMES_PROPERTY`), thread-confined two-phase ingestion pipeline, `ReferenceGraphIndex` concurrent lookups, deterministic single-pass OSGi Metatype 1.4 name normalization engine.
- **Success Criteria**: Sub-second graph construction across enterprise multi-module projects ($<250\text{ ms}$ for 5,000 classes); zero lock contention; $O(1)$ query lookups for cross-boundary resolution.

### Milestone 4 (M4): Dead Code Detection Rules & Violation Engine
- **Objective**: Implement Phase 1 detection algorithms with rigorous false-positive elimination.
- **Key Deliverables**: Rule evaluators for `AEM-DEAD-001` (DS 1.4 §112.8.2 `ActivePids(K)` with default PID suppression and `"$"` wildcard, detecting `ORPHANED_OCD_NAMED_CONFIG`), `AEM-DEAD-002A` (ghost properties with typed key stripping), `AEM-DEAD-002B` (multi-component OCD call aggregation via `CONSUMES_PROPERTY` in-degree), `AEM-DEAD-003A` (service interface package exports and tightened `immediate = true`), `AEM-DEAD-003B` (Sling Models), and `AEM-DEAD-003C` (inactive delayed components); Platform PID Catalog; 11-category System Entry Point Catalog (including Whiteboard SPIs).
- **Success Criteria**: Flawlessly flags real-world production defects (`ContentSyncReplicationServletConfig.cfg.json` and `FlourishCmpConfig.cfg.json`) while maintaining zero false positives on whiteboard servlets, SPI injectors, and platform configs.

### Milestone 5 (M5): Delivery Mechanisms (Maven Plugin & CLI) & Exporters
- **Objective**: Package the analyzer into developer-ready delivery forms with industry-standard CI reporting.
- **Key Deliverables**: `aem-analyser-maven-plugin`, `aem-analyser-cli`, canonical OASIS SARIF 2.1.0 exporter (with `ruleIndex`, `%SRCROOT%`, and 1-based coordinates), SonarQube Generic Issue JSON exporter (with reactor-root path normalization and 0-based coordinate transformation $C_{\text{sonar}} = C_{\text{jp}} - 1$), ANSI console formatter.
- **Success Criteria**: Successful standalone CLI execution, Maven plugin build enforcement, valid SARIF rendering in GitHub/Azure, and compliant SonarQube ingestion.

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
/Users/rvenat01/Documents/rohith/aem-code-analyser/
├── PROJECT.md                                # Global project index & architecture blueprint
├── pom.xml                                   # Root Maven parent POM
├── docs/                                     # Technical specifications & documentation
│   └── AEM_CODE_ANALYSER_SPECIFICATION.md    # Authoritative 9-section specification & plan
├── aem-analyser-core/                        # Core analysis engine & domain logic
│   ├── pom.xml
│   └── src/
│       ├── main/java/io/github/aemtools/analyser/core/
│       │   ├── api/                          # Public interfaces & orchestration
│       │   ├── discovery/                    # Multi-module POM discovery & classification
│       │   ├── parser/                       # Java AST & OSGi config parsers
│       │   │   ├── java/                     # JavaParser visitors (Component, OCD, Model)
│       │   │   ├── config/                   # Jackson & Felix OSGi config parsers
│       │   │   └── bytecode/                 # ASM dependency indexer
│       │   ├── model/                        # Domain models, nodes, edges, AST symbols
│       │   ├── graph/                        # JGraphT reference graph & secondary indices
│       │   ├── rules/                        # Phase 1 detection rules & catalogs
│       │   │   ├── catalogs/                 # Platform PIDs & System Entry Points
│       │   │   └── impl/                     # AEM-DEAD-001, 002, 003 rule implementations
│       │   └── export/                       # SARIF 2.1.0, Sonar JSON, ANSI console exporters
│       └── test/java/io/github/aemtools/analyser/core/
│           ├── discovery/                    # Discovery engine tests
│           ├── parser/                       # Parser tests & golden files
│           ├── rules/                        # Rule unit tests & edge cases
│           └── fixtures/                     # Test POMs, configs, and Java classes
├── aem-analyser-maven-plugin/                # Apache Maven plugin integration
│   ├── pom.xml
│   └── src/
│       ├── main/java/io/github/aemtools/analyser/maven/
│       │   ├── CheckMojo.java                # aem-analyser:check (Quality Gate)
│       │   └── ReportMojo.java               # aem-analyser:report (Site/Report)
│       └── test/java/io/github/aemtools/analyser/maven/
├── aem-analyser-cli/                         # Standalone Picocli command-line tool
│   ├── pom.xml
│   └── src/
│       ├── main/java/io/github/aemtools/analyser/cli/
│       │   ├── AnalyserCli.java              # Main CLI entry point
│       │   └── commands/                     # Subcommands & parameter validation
│       └── test/java/io/github/aemtools/analyser/cli/
└── .agents/teamwork/                         # Agent coordination metadata (ONLY)
```

---

## 7. Verification Strategy & Independent Validation

Every phase and feature of the analyzer must be independently verifiable against real-world test cases:
1. **Unit Testing**: Golden-file test suites validating JavaParser annotation visitors, Jackson token location tracking, Felix `.config` parser, Metatype 1.4 name normalization, and individual rule evaluators.
2. **Integration Testing Against Reference Repositories**:
   - **Standard Archetype (`rohith-aem-portfolio`)**: Confirms zero false positives on standard factory configurations (`SiteIndexingProfileImpl~en.cfg.json`), underscore properties (`webconsole_configurationFactory_nameHint`), and whiteboard servlets.
   - **Enterprise Multi-Tenant (`aemaacs-life`)**: Confirms detection of the two real-world production defects:
     - `com.mandg.core.servlets.contentsync.ContentSyncReplicationServletConfig.cfg.json` (Flagged as Orphaned / Mismatched PID).
     - `com.mandg.core.config.FlourishCmpConfig.cfg.json` (Flagged as Orphaned OCD named config).
     - Mixed `.config` parsing in `whitelabel` without syntax failure.
3. **Format Conformance**:
   - Validate exported `.sarif` files against the official OASIS SARIF 2.1.0 JSON Schema using JSON schema validators.
   - Validate `sonar-report.json` against SonarQube Generic Issue Import specifications.
