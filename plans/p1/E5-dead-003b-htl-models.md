# E5 — AEM-DEAD-003B HTL / JCR / model reference coverage

Status: Done (partial)
Priority: P1
Area: aem-analyser-core / `rules.impl.AemDead003BRule`, `discovery.HtlScanner`,
`discovery.JcrComponentScanner`, `parser.java.visitor.SlingModelVisitor`

Problem / opportunity:
Already handled (verified — do not re-implement):
- `data-sly-use` with a quoted class name, a single-quoted name, or the `${'…'}` literal form.
- `HtlUsage.isJavaClass()` already excludes paths, `.html`, `.js`, `.jsp` and treats dotted or
  capitalised targets as Java classes; `AemDead003BRule` matches both FQN and simple name and also
  matches declared adapter interfaces.
- `ModelFactory.createModel` / `getModel` / `adaptTo(Target.class)` are all turned into adapt edges
  by `JavaSourceParser` (l.145-166) — the backlog wording implies this is missing; it is not.
- `@Model(adapters=…)` with fallback to implemented types; `resourceType` binding against scanned
  `cq:Component` resource types.
Real gaps:
1. Dynamic targets: `data-sly-use.model="${'com.x.Model' @ …}"` with an expression (not a single
   literal) and `data-sly-use.tpl="template.html"` are not resolved, and the attribute may span
   lines — the scanner is line-based, so multiline attributes are missed entirely.
2. `data-sly-resource` and `data-sly-include` are never scanned, so a model that is only used via
   `data-sly-resource="${foo @ resourceType='x/y'}"` looks unused.
3. `@Via` is unparsed, so a model used only through a `@Via`-injected delegate is flagged.
4. `@Exporter` (`org.apache.sling.models.annotations.Exporter`) is unparsed, so a model used only as
   a JSON/XML exporter is flagged.
5. JSP usage of a model (`<sly data-sly-use…>` aside, `com.x.Model` FQN in a `.jsp`) is not scanned —
   only `AEM-CLOUD-002` looks at JSPs.
6. `JcrComponentScanner` requires the exact substring `jcr:primaryType="cq:Component"`, so
   single-quoted attributes, attribute-order variants and `cq:Component` under a prefix are missed —
   the model then loses its resourceType binding and 003B fires.

Desired behavior:
- `HtlScanner` extracts attribute values that span lines and records the attribute kind.
- `resourceType` overrides in HTL count as component/model usage.
- All `@Model` usage channels (Java, HTL, exporter, `@Via`) are visible to 003B.

Files to change:
- `core/discovery/HtlScanner.java` — regex over the whole file (not per line) with `DOTALL`,
  capture `data-sly-use`, `data-sly-resource`, `data-sly-include`, and `resourceType=` overrides;
  compute line/column from offsets.
- `core/model/htl/HtlUsage.java` — add `Kind` (USE, RESOURCE, INCLUDE) with a defaulted constructor.
- `core/parser/java/visitor/SlingModelVisitor.java` — record `via` targets and `@Exporter` presence.
- `core/rules/impl/AemDead003BRule.java` — treat export/via/resource usages as consumption.
- `core/discovery/JcrComponentScanner.java` — parse `.content.xml` with a tolerant
  `jcr:primaryType\s*=\s*['"]cq:Component['"]` pattern.

Implementation steps:
1. Rework `HtlScanner.scanFile` to read the file once, run three compiled patterns with
   `Matcher.find()` over the buffer, and compute `line = 1 + count('\n', 0..start)`.
2. Keep `scanFile(Path, List<HtlUsage>)` signature so `AemDead003BRule` and tests compile.
3. Add `SlingModelNode.viaTargets()` / `exporters()` (defaulted) and consume them in 003B.
4. Replace the `JcrComponentScanner` substring test only after adding a single-quote fixture.

Tests to add:
- `HtlScannerTest`: multiline `data-sly-use`, `${'com.x.M'}` variable form, `data-sly-resource`
  with `resourceType`, single-quoted attribute.
- `AemDead003BRuleTest`: model used only via `@Via`; via `@Exporter`; via `data-sly-resource`;
  genuinely unused model (one violation).
- `JcrComponentScannerTest`: single-quoted `jcr:primaryType`.

Acceptance criteria:
- The four HTL channels above produce zero `UNUSED_SLING_MODEL`.
- A model with no usage in any channel still produces exactly one violation.
- Scanner line numbers in fixtures match the source line.

Risks / notes:
- Whole-file matching makes `HtlScanner` memory-bound by template size — fine for HTL (KBs).
- `data-sly-resource` with a dynamic resourceType cannot be resolved statically; treat as
  "used" and count it in diagnostics rather than guessing a class.

---

## Implementation (Sling Models use graph)

- `SlingModelNode` records `referencedTypes` (field/parameter/return types, superclass, interfaces)
  and whether the model is nested.
- `AEM-DEAD-003B` treats a model as used when another model references it, so `@Self`,
  `@ChildResource List<T>`, `@Named` and base-class usage no longer produce false positives.
- Enterprise project validation: 18 → 1 unused-model findings; the remaining one is genuinely dead.
- Remaining: multiline/dynamic `data-sly-use`, `data-sly-resource`, `@Via` and `@Exporter` channels.
