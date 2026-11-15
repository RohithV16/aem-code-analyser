# C13 — Resource lifecycle false positives (`AEM-PERF-001/002`)

Status: Done (wave 1, 2026-11-15)
Priority: P0
Area: aem-analyser-core / `parser.java.visitor.ResourceLeakVisitor`

Verified evidence from the real-project baseline (both ERROR-level):
- `asset.getResourceResolver()` was treated as a factory acquisition, although the resolver is
  borrowed from the asset and must **not** be closed.
- `resolver.adaptTo(Session.class)` inside `try (ResourceResolver resolver = …)` was reported as an
  unclosed session, although closing the resolver releases it.

Problem: four of seven ERRORs on a clean project were lifecycle false positives, so the gate was not
adoptable.

Desired behavior:
- Only resolvers obtained from a `ResourceResolverFactory` are ownership candidates.
- A session adapted from a resolver that is a try-with-resources resource is not a leak.

Files to change (landed):
- `aem-analyser-core/.../parser/java/visitor/ResourceLeakVisitor.java`
  - `isResourceResolverFactoryCall(...)` — `getServiceResourceResolver`/
    `getAdministrativeResourceResolver` always, `getResourceResolver` only when the receiver looks
    like a factory.
  - `isScopeManagedByTryWithResources(...)` — receiver declared as a TWR resource.

Tests to add (landed):
- `parser/java/visitor/ResourceLeakVisitorTest` — borrowed resolver, unclosed factory resolver,
  adapted session inside/outside TWR.

Acceptance criteria: portfolio baseline loses both PERF false positives while a genuinely unclosed
factory resolver/session still fires.

Risks / notes: heuristic receiver matching avoids full symbol resolution. `getResourceResolver()` on
a variable named something other than `*factory*` is treated as borrowed (fail-open, fewer FPs).
