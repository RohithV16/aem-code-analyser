# C14 — `AEM-CLOUD-003` never fired for `getServiceResourceResolver(null)`

Status: Done (wave 1, 2026-11-15)
Priority: P0
Area: aem-analyser-core / `rules.impl.AemCloud003Rule`

Verified evidence from the real-project baseline: both service-resolver calls in
`CursorStateServiceImpl` pass `null` (bundle default subservice). The rule skipped null/blank
subservices entirely, and the mapping parser ignored bundle-level entries
(`com.example.bundle=[user]`, no `:` subservice). The project has no service-user mapping at all, so
the login fails at runtime — a genuine deployment blocker that the tool missed.

Problem: null-subservice calls were invisible, and bundle-level mappings were not understood.

Desired behavior:
- Parse bundle-level `user.mapping` entries and treat them as default mappings.
- A null/blank subservice call is an ERROR (`UNMAPPED_DEFAULT_SUBSERVICE`) when no bundle-level
  mapping exists anywhere in the reactor.
- Explicit non-blank subservices keep the existing `UNMAPPED_SUBSERVICE_USER` behaviour.

Files to change (landed):
- `aem-analyser-core/.../rules/impl/AemCloud003Rule.java` — capture `defaultMappedBundles`; branch on
  null/blank subservice.

Tests to add (landed):
- `AemCloudAndSecRulesTest` — null subservice with no mappings → ERROR; null subservice with a
  bundle-level mapping → no finding.

Acceptance criteria: portfolio baseline now reports `AEM-CLOUD-003` for both null-subservice calls;
a project with a bundle-level mapping stays clean.

Risks / notes: the check is reactor-global (any bundle-level mapping suppresses), because bundle
symbolic names are not linked to Java packages yet. That is deliberately fail-open: never a false
positive, at the cost of missing a mis-scoped mapping.

### Follow-up: unresolved subservice constants (generic fix)

Reported from a different project: `getServiceResourceResolver(...)` with a cross-file constant
(`SOME_SERVICE_SUBSERVICE_NAME`) was flagged as `UNMAPPED_SUBSERVICE_USER`. The extractor records the
Java symbol when it cannot resolve the constant, so comparing it to literal `user.mapping` values is a
guaranteed false positive.

- `AemCloud003Rule` now fails open when the subservice looks like an unresolved Java constant
  (`UPPER_SNAKE_CASE`), instead of emitting an ERROR.
- `SubserviceCallExtractor` resolves `Constants.FIELD` references when the constant is declared in the
  same compilation unit, and matches subservice map keys case-insensitively.
- Removed the example-project name from a comment; a repo-wide grep for sample-project identifiers in
  production code is now clean.
- Tests: `AemCloudAndSecRulesTest` (constant is not flagged) and `SubserviceCallExtractorTest`
  (field-access constant resolves to its value).
