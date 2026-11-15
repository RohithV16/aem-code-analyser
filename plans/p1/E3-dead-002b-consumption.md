# E3 — AEM-DEAD-002B property-consumption coverage

Status: Done (partial)
Priority: P1
Area: aem-analyser-core / `rules.impl.AemDead002BRule`,
`parser.java.visitor.ComponentPropertyConsumptionVisitor`, `parser.java.JavaSourceParser`

Problem / opportunity:
Already handled (verified — do not re-implement):
- Constructor injection: `visit(ConstructorDeclaration)` records parameters as receivers, and calls
  through them are tagged `CONSTRUCTOR_INJECTION` (l.74-77, 109-111).
- Field access (`config.foo()`), `this.config.foo()`, local variables, method parameters and getter
  bodies are all captured — a getter that returns `config.foo()` already counts as consumption.
- `@Activate` method parameters are captured by the same `visit(MethodDeclaration)`.
- The rule reads in-degree on the OCD method node, so aggregation across all designating components
  is O(1).
Real gaps:
1. `@ComponentPropertyType` is not parsed at all (no visitor reads the annotation). Config
   interfaces built from it have no OCD node and are invisible to 002B and 002A.
2. Consumption through a method-call receiver (`getConfig().foo()`, `this.config().foo()`) is dropped
   because `receiverNameOf` only accepts `NameExpr` / `this.field`.
3. Consumption through an inherited field declared in a superclass is not seen (the visitor walks one
   `TypeDeclaration`).
4. Method references / lambdas (`this::getFoo`, `service.accept(config.foo())` outside a call scope)
   are only partially covered.
5. `@Reference` bind methods (`void bind(Service s)`) are ignored by `OsgiComponentVisitor`
   (field-only extraction) — relevant to both E3 and E4.

Desired behavior:
- A property is "consumed" if any designating component reads it through field, constructor,
  parameter, getter, method-reference or `@ComponentPropertyType` accessor.
- Unknown constructs degrade to "consumed" (never a false positive) and are counted in diagnostics.

Files to change:
- `core/parser/java/visitor/ComponentPropertyConsumptionVisitor.java` — accept
  `MethodCallExpr` receivers whose resolved type is the config type; add `LambdaExpr` /
  `MethodReferenceExpr` handling; expose `unresolvedReceivers` for diagnostics.
- `core/parser/java/visitor/MetatypeOcdVisitor.java` — synthesise an OCD from an annotation type
  annotated `@ComponentPropertyType` (treat members as attributes, `@AttributeDefinition` id wins).
- `core/parser/java/JavaSourceParser.java` — run the OCD visitor for `@ComponentPropertyType` types
  in addition to `@ObjectClassDefinition`, and pass inherited fields by resolving the superclass in
  the same unit.

Implementation steps:
1. Record a `Map<String, String> inheritedFieldTypes` by resolving the extended type name against
   `classLookup` in a second pass; keep the single-pass contract for the common case.
2. Extend `receiverNameOf` to resolve nested `MethodCallExpr` scopes via `receiverTypes`.
3. Treat syntactically unresolved member accesses on a config-typed receiver as consumption and add
   a diagnostic counter (E11 payload) so silent coverage loss is measurable.
4. Add a `--strict-consumption` switch later (F2) rather than changing report semantics here.

Tests to add:
- Fixtures: `@ComponentPropertyType` interface + `@Designate(ocd=…)` consumer + one unused attribute
  (expect exactly one `AEM-DEAD-002B`).
- Getter-only consumption (no violation); `getConfig().foo()` (no violation);
  inherited config field (no violation); unused attribute (violation).
- Negative control: attribute consumed only from a test class must NOT count — assert current
  behaviour and document it (graph has no test-source distinction).

Acceptance criteria:
- Every fixture above emits the expected count.
- No new violation on the existing `EndToEndAnalysisTest` fixture.
- New code contains no `catch`-and-ignore for parse errors (report through E11).

Risks / notes:
- Counting test classes as consumers is arguably wrong but deliberate: false negatives are cheaper
  than false positives. Record it as a documented limitation in `PROJECT.md`.
- Do not switch to a full binding-resolution pass; JavaParser symbol solving is out of budget here.

---

## Implementation

- `@ComponentPropertyType` annotations are now parsed as OCDs (`MetatypeOcdVisitor`), so their
  members participate in 002A/002B instead of being invisible.
- Method-level and constructor-parameter `@Reference` injections are captured (shared with E4).
- Remaining: inherited OCD attributes across files, method-call receivers (`getConfig().foo()`),
  superclass fields and full lambda/method-reference coverage.
