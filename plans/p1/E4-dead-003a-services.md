# E4 — AEM-DEAD-003A service provision, injection and export semantics

Status: Verified
Priority: P1
Area: aem-analyser-core / `rules.impl.AemDead003ARule`, `graph.ReferenceGraphBuilder`,
`parser.java.visitor.OsgiComponentVisitor`, `rules.catalogs.SystemEntryPointCatalog`,
`discovery.ExportPackageCollector`

Problem / opportunity:
Wave-2 status (2026-11-15) — **service semantics implemented**:
- `OsgiComponentVisitor` now distinguishes an absent `service` member (fall back to implemented
  interfaces) from an explicit `service` member (use it verbatim, including `service = {}`), while
  still recording implemented interfaces for entry-point detection.
- `SlingModelVisitor` now records referenced types (fields, parameters, return types, superclass,
  interfaces); `AEM-DEAD-003B` uses them to recognise models consumed through injection. Nested
  models are skipped. Enterprise project validation: 18 → 1 finding.
- Method-level `@Reference` bind methods and DS 1.4 constructor-parameter `@Reference` injections are
  now captured, so services consumed only that way are not flagged.
- Remaining E4 work: `servicefactory`, bnd continuation lines, and the two missing whiteboard SPI
  catalog entries.

Already handled (verified — do not re-implement):
- Self-injection is excluded in `ReferenceGraphBuilder.resolveInjection` (l.351-358), so a component
  that injects its own interface is not "self-used".
- `AemDead003ARule` already unions service interfaces, the superclass and all implemented interfaces
  before the entry-point check, and already honours `ignoredServices`.
- `ExportPackageCollector` strips bnd directives, supports `com.foo.*` hierarchy matching and reads
  both `bnd.bnd` and the `Export-Package` element of bnd/bundle plugins.
- `SystemEntryPointCatalog` already covers servlets, filters, event handlers, jobs, schedulers,
  workflow, replication, health checks, post operations, rewriters, models SPI, CAConfig SPI,
  `SlingRequestListener`, webconsole plugins and `BundleActivator`.
Real gaps:
1. `OsgiComponentVisitor` adds **all implemented interfaces** to `serviceInterfaces` (l.71-79)
   regardless of the `service` attribute. `@Component(service = {})` therefore invents services, and
   `@Component(service = Object.class)` keeps the concrete interfaces → false "unused service".
2. `servicefactory` is not parsed (legacy but still present in real code).
3. `@Reference` is read from fields only (l.133-159). Method-level `@Reference` bind methods and
   `@Reference` on activate-method parameters produce no `INJECTS_SERVICE` edge → false 003A hits
   on services consumed only that way. Cardinality/policy are not recorded either.
4. Multi-line bnd continuation (`\` at end of line) and `-exportcontents` are not read; `@Export`
   annotation detection exists (`JavaSourceParser` l.127) but `org.osgi.annotation.bundle.Export`
   inside `package-info.java` is only picked up if the type is a plain class node.
5. `SystemEntryPointCatalog` lacks `ResourceProvider`/`DynamicResourceProvider` and
   `org.apache.sling.datasource.DataSourceFactory` (booked as whiteboard SPIs in the backlog).

Desired behavior:
- `serviceInterfaces` mirrors DS semantics: use the explicit `service` member when present (even if
  empty), else the directly implemented interfaces.
- Any `@Reference` form creates an injection edge.
- Whiteboard SPIs in the catalog are complete for the listed categories.

Files to change:
- `core/parser/java/visitor/OsgiComponentVisitor.java` — parse `service`, `servicefactory`;
  collect `@Reference` from fields, methods and method parameters; record `cardinality`/`policy`.
- `core/model/graph/node/OSGiComponentNode.java` — add `serviceExplicit`, `serviceFactory`,
  reference metadata (defaulted constructors to keep source compatibility).
- `core/graph/ReferenceGraphBuilder.java` — emit `INJECTS_SERVICE` for the new reference forms.
- `core/discovery/ExportPackageCollector.java` — join backslash continuations before matching.
- `core/rules/catalogs/SystemEntryPointCatalog.java` — add the two SPI entries.

Implementation steps:
1. Detect `service` presence via `AnnotationUtils.memberValues(...)` plus the raw `MemberValuePair`;
   distinguish "absent" (fall back to implements) from "present but empty" (no services).
2. Add method-level `@Reference` extraction; map to the same `Map<String,String>` keyed by member
   name so `PendingServiceInjection` construction in `JavaSourceParser` (l.110-113) is unchanged.
3. Unfold `\`-terminated lines in `bnd.bnd` before applying `BND_HEADER_PATTERN`.
4. Add catalog entries with a fixture each; do not add "maybe" SPIs.

Tests to add:
- `AemDead003ARuleTest`: `service = {}` on a class implementing an interface (no violation);
  `service = Object.class` (violation when uninjected); method-level `@Reference` consumer
  (no violation); `@Reference(cardinality=OPTIONAL)` still creates an edge;
  `servicefactory = true` (recorded, no behaviour change).
- `ExportPackageCollectorTest`: continuation lines, `com.foo.*` and exact package.
- `SystemEntryPointCatalogTest`: one component per new SPI entry.

Acceptance criteria:
- `service = {}` never produces `UNUSED_OSGI_SERVICE`.
- Services consumed only via bind methods are not flagged.
- No regression in `EndToEndAnalysisTest`.

Risks / notes:
- Adding catalog entries reduces true positives by design. Each entry must carry a comment naming the
  framework that instantiates it.
- `@Reference` on a constructor parameter (DS 1.4) arrives via `@Activate` parameter parsing; make
  sure the same parameter is not double-counted.
