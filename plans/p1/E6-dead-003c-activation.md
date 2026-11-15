# E6 — AEM-DEAD-003C Declarative Services activation semantics

Status: Verified
Priority: P1
Area: aem-analyser-core / `rules.impl.AemDead003CRule`, `parser.java.visitor.OsgiComponentVisitor`,
`rules.catalogs.SystemEntryPointCatalog`

Problem / opportunity:
Wave-3 status (2026-11-15) — **implemented**:
- `factory`, `enabled` and programmatic activation (`ServiceTracker`/`BundleContext`) are parsed into
  `OSGiComponentNode`.
- `AEM-DEAD-003C` skips factory, disabled, programmatically activated and `REQUIRE`-policy components;
  a plain `immediate = false` component with no service still violates.
- Gap 5 (DS default-immediate false negatives) remains deliberately unchanged; it would increase
  findings and needs a separate fixture wave.

Already handled (verified — do not re-implement):
- `immediate` is parsed with an explicit "was it set?" flag (`isImmediateExplicitlySet()`), the rule
  only flags `explicitly set && false`, and components that provide services are excluded.
- `SystemEntryPointCatalog` exemption already covers Runnable, Servlet/Filter, EventHandler, job
  consumers, workflow and the other framework-instantiated types.
Real gaps (all confirmed absent from `OsgiComponentVisitor`):
1. `@Component(factory = "…")` is not parsed. Factory components are instantiated on demand from a
   factory configuration, so "no service + immediate=false" is legal for them — today they are
   flagged (false positive).
2. `@Component(enabled = false)` is not parsed. A disabled component is dead by construction and
   should get its own subtype instead of `INACTIVE_DELAYED_COMPONENT`.
3. `ServiceTracker` / `BundleContext` usage is not detected. A component that is instantiated by
   another component through the raw OSGi API is not dead.
4. `configurationPolicy = REQUIRE` is ignored by 003C. Such a component only activates when its PID
   is configured, so "no service + immediate=false + REQUIRE" is not automatically dead.
5. DS 1.4 default: for a component with **no** service and **no** factory, an unspecified `immediate`
   resolves to "not activatable by anyone", yet `isImmediateExplicitlySet()` gates those out — a
   false negative. Changing this is a behaviour change and needs fixture-level evidence.

Desired behavior:
- Factory, disabled and programmatically-instantiated components are never reported as inactive
  delayed components.
- The `immediate` default is explicit in the model instead of "absent means skip".

Files to change:
- `core/parser/java/visitor/OsgiComponentVisitor.java` — parse `factory`, `factoryProperty`,
  `enabled`; add a `usesBundleContext` / `usesServiceTracker` flag derived from field and method
  types/imports.
- `core/model/graph/node/OSGiComponentNode.java` — add `factoryId`, `enabled`,
  `programmaticActivation` (defaulted constructors).
- `core/rules/impl/AemDead003CRule.java` — early-return for factory/enabled=false/REQUIRE/
  programmatic activation; optional new subtype for the default-immediate case.
- `core/model/report/RuleId.java` — only if a new subtype is needed (subtypes are strings, so
  probably not).

Implementation steps:
1. Parse `factory`/`enabled` exactly like `name` (`AnnotationUtils.memberValue`).
2. Detect `ServiceTracker`, `ServiceTrackerCustomizer`, `BundleContext`, `ServiceRegistration` and
   `BundleActivator` in declared field types, method parameter types and imports.
3. Add `policy == REQUIRE` to the skip conditions with a comment citing DS 1.4 §112.4.4.
4. Only after the portfolio validation is re-run, decide on gap 5; keep it behind a separate
   sub-task so the FP baseline stays controlled.

Tests to add:
- Fixtures: factory component (no violation), `enabled=false` (expect the chosen subtype),
  `ServiceTracker` field (no violation), `configurationPolicy=REQUIRE` with a matching config
  (no violation), plain `immediate=false` + no service (violation).
- Parameterized over `immediate` absent / `true` / `false`.

Acceptance criteria:
- No violation for factory, disabled or ServiceTracker components.
- `immediate = false` with no service, no factory and no REQUIRE still violates.
- `EndToEndAnalysisTest` unchanged.

Risks / notes:
- Gap 5 is the only item that can *increase* findings. Land it last and compare against
  `plans/validation/portfolio-baseline.md`; if any of the new hits is a framework instantiation
  pattern, add it to the entry-point catalog instead of reverting.
