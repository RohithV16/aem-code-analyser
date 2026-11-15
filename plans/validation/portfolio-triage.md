# Triage — `archetype-aem-project` (read-only pass)

Date: 2026-11-15 · Target repo: `/path/to/archetype-aem-project` · **target repo untouched**
(no checkout/reset/clean; no files written inside it; all output and probes under
`/private/var/folders/2d/yvkjbkqx4hj__hy29qgrqtxw0000gp/T/`).

- Tool repo commit: `387dad2` · version `aem-analyser 1.0.0-SNAPSHOT`
- Jar: `aem-analyser-cli/target/aem-analyser-cli.jar` (35,576,178 B, `sha256:04448678910dd21b…`)
- Runtime: OpenJDK 21.0.8 (Homebrew)

Purpose: re-triage every finding against the source, and hunt for analyser assumptions that were
modelled on sample projects (single-bundle, Java-only Sling Models, vendor-free source trees).

---

## 1. Command and counts

```bash
java -jar aem-analyser-cli/target/aem-analyser-cli.jar \
  --project-root /path/to/archetype-aem-project \
  --format SARIF,CONSOLE \
  --output /private/var/folders/2d/yvkjbkqx4hj__hy29qgrqtxw0000gp/T/aem-analyser-portfolio-triage \
  --no-color --verbose
```

| Item | Value |
|---|---|
| Exit code | **1** (`BUILD FAILURE: 5 critical cross-boundary errors detected.`) |
| Violations | **13** — 5 ERROR / 8 WARNING / 0 INFO |
| Duplicate `(rule, file, line, col)` tuples | **0** (13 results = 13 unique) |
| Execution time | 4.2 s (second, warm run; identical result set) |
| Output | `aem-analyser.sarif` (13 results), `console.log` |
| `--verbose` | emitted **nothing** — no analysis diagnostics (all Java/config/HTL parsed cleanly) |

Scan statistics: Modules 11 · Bundles 1 · OsgiConfigs 22 · CloudConfigs 0 · Components 18 ·
CqComponents 40 · HtlUsages 10 · SlingModels 1 · CodeBlocks 16 · Edges 264 ·
Java 51 classes / 2,900 LOC · duplication 14 lines (0.5 %) in 1 block.

Inventory cross-checks (all consistent with source): 22 `.cfg.json` in 10 runmode folders; 10
`data-sly-use` occurrences (= `HtlUsages: 10`); 1 `@Model` class (`HelloWorldModel`), referenced
statically from `helloworld.html`; 2 `getServiceResourceResolver` call sites, both in
`CursorStateServiceImpl`.

---

## 2. Triage table

| Rule | total | FP | TP | policy |
|---|---:|---:|---:|---:|
| AEM-CLOUD-003 (unmapped subservice / service user) | 2 | 0 | **2** | 0 |
| AEM-SEC-001 (HTL `context='unsafe'`) | 2 | 0 | 0 | **2** |
| AEM-CLOUD-001 (plaintext secret) | 1 | **1** | 0 | 0 |
| AEM-DEAD-002A (ghost config property) | 1 | 0 | **1** | 0 |
| AEM-DUP-010 (duplicate code block) | 1 | 0 | **1** | 0 |
| java:S1166 (exception context) | 3 | **1** | 2 | 0 |
| java:S1192 (duplicated literal) | 1 | 0 | 0 | **1** |
| java:S2208 (wildcard import) | 1 | 0 | **1** | 0 |
| java:S1155 (`size() > 0`) | 1 | 0 | **1** | 0 |
| **Total** | **13** | **2** | **8** | **3** |

Gate impact: of the 5 ERRORs, **2 are policy calls** (vendor HTL boilerplate) and **1 is a false
positive** (local-only dev default). Only `AEM-CLOUD-003` × 2 justifies a failing exit code.

---

## 3. FALSE positives (detail)

| # | File:line | Rule | Why it is wrong |
|---|---|---|---|
| 1 | `ui.config/…/osgiconfig/config.local/com.rohithaem.portfolio.core.services.elastic.impl.SearchConnectionServiceImpl.cfg.json:4` | AEM-CLOUD-001 | The value is not a secret: `"esPassword": "admin123"` is **identical to the OCD default** declared on `SearchConnectionServiceImpl.Config.esPassword()` (`@AttributeDefinition ... default "admin123"`, `SearchConnectionServiceImpl.java:54`). Additionally `config.local` is a developer-only runmode that Cloud Manager never deploys; the credential targets the local Docker Search from `esUrl: http://host.docker.internal:9200`. The message's claim ("contains a plaintext hardcoded secret") is therefore false, and the *real* anti-pattern — a string default that looks like a credential, in Java — is not covered by any rule. |
| 2 | `it.tests/…/it/tests/PublishPageValidationIT.java:87` | java:S1166 | `catch (IOException ignored) {}` inside `@AfterClass afterClass()` closing `adminPublish` — a documented teardown ignore (comment explains the commons-io `IOUtils.closeQuietly` deprecation workaround). Rethrowing would fail the whole IT suite on cleanup; logging adds nothing. The variable is deliberately named `ignored`. Note: the earlier baseline described this as an "integration test assertion path" — that description is wrong; the catch is client cleanup, not an assertion. |

Net effect of just these two: ERROR count would drop 1 (CLOUD-001), WARNING count 1 (S1166).

---

## 4. POLICY calls (real finding, wrong gate for this repo)

| # | File:line | Rule | Why policy, not FP |
|---|---|---|---|
| 3 | `ui.apps/…/components/xfpage/customfooterlibs.html:25` | AEM-SEC-001 | The `@ context='unsafe'` is real, but this file is **Adobe archetype boilerplate** (Apache-2.0 Adobe copyright header, unchanged since the project's initial commit) and the idiom is the Core Components `Page.htmlPageItems` contract: `data-sly-element="${item.element.name @ context='unsafe'}"`. `item.element.name` comes from the server-side `Page` model (element names, not request input) and attributes go through `data-sly-attribute`, which stays escaped. Editing vendor files is worse than suppressing them; as an ERROR it gates every archetype-derived project on day one. |
| 4 | `ui.apps/…/components/xfpage/customheaderlibs.html:29` | AEM-SEC-001 | Same file family, same reasoning (header variant of the same loop). |
| 5 | `core/…/impl/SearchConnectionServiceImpl.java:128` | java:S1192 | `payload.put("status", "new")` duplicates the *value* of `STATUS_KEY`, but the constant is used for the HTTP **response** status (`result.put(STATUS_KEY, code)` at line 96). They are different semantic roles that happen to share a word; adopting the suggested constant would couple the Search document schema to the response map. The real fix (a distinct `PAYLOAD_STATUS_FIELD` constant) is not what the message recommends. |

---

## 5. TRUE positives (detail)

| File:line | Rule | Evidence |
|---|---|---|
| `core/…/impl/CursorStateServiceImpl.java:29` | AEM-CLOUD-003 | `getServiceResourceResolver(null)` in `getCurrentCursor()`. No `ServiceUserMapperImpl.amended` exists anywhere under `ui.config/src` (grep over all `.java`/`.json`/`.xml`/`.cfg` returns nothing). Bundle symbolic name is `rohithaem-portfolio.core` (`core/pom.xml` `Bundle-SymbolicName: ${project.groupId}.${project.artifactId}`, confirmed in the built manifest). The only mapping in the tree is **stale build output**: `ui.config/target/classes/…/config/org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~rohith.cfg.json` → `com.rohithaem.portfolio:resource-Resolver-user=[service-user-test]`, i.e. wrong bundle id *and* a named subservice, so it would not satisfy a null-subservice login even if it shipped. At runtime the login resolves to null → NPE/`ServiceUnavailableException` on every cursor read. |
| `core/…/impl/CursorStateServiceImpl.java:42` | AEM-CLOUD-003 | Same defect in `persistCursor()`; second call site, same root cause. Both calls are correctly wrapped in try-with-resources (no leak finding, correct). |
| `core/…/impl/ScheduledPollingServiceImpl.java:214` | AEM-DUP-010 | Verified real: lines **114–127** and **216–227** are token-identical modulo the request variable (`client.execute(httpGet)` vs `client.execute(post)`) — `SSLConnectionSocketFactory` + `RequestConfig` + `HttpClients.custom()` try-with-resources block duplicated in `pollJournalEvents` and `fetchAccessToken`. Location is off by 2 lines (see §6, fix 4). |
| `core/…/impl/DocumentReplicationEventHandler.java:51` | java:S1166 | `catch (IllegalStateException e) { LOG.warn("Unknown replication type: [{}]", actionType); }` — logs the action type but drops `e`; the strategy-mapping failure reason is lost. Genuine, low severity. |
| `core/…/impl/ScheduledPollingServiceImpl.java:249` | java:S1166 | `getBaseUrl()` catches `Exception` from `new URI(url)` and returns the raw URL without logging: a malformed cursor silently degrades to the unfiltered URL (also the SSRF surface tracked as F9). Genuine; a debug log would be enough. |
| `it.tests/…/GetPageIT.java:24` | java:S2208 | `import org.junit.*;` — valid style finding. |
| `it.tests/…/PublishPageValidationIT.java:114` | java:S1155 | `references.size() > 0` — valid style finding. |
| `ui.config/…/config.local/…ScheduledPollingServiceImpl.cfg.json:9` | AEM-DEAD-002A | Genuine and the highest-value non-security finding: `"disabled": true` has no `disabled()` in `ScheduledPollingServiceImpl.Config` (`ScheduledPollingServiceImpl.java:50-73`) and the component is `configurationPolicy = REQUIRE`. The flag does nothing; the operator believes polling is off. (It also means the component is *unsatisfied* wherever `config.local` is absent — i.e. on publish — but that is a separate design question, not a finding.) |

Positive controls re-confirmed (correctly silent — keep this behaviour):

- Platform/vendor PIDs: `CORSPolicyImpl~`, `MobileEmulatorProvider~` (incl. its `README` key),
  `TableOfContentsFilter~`, `LogManager.factory.config~`, `RepositoryInitializer~`,
  `JcrResourceResolverFactoryImpl` → 0 findings.
- Runmode overrides (`config.author/author.dev/author.prod/author.stage` for the same PID) → no
  AEM-DUP-002.
- Resolver lifecycle: `CursorStateServiceImpl` (TWR) and `SiteIndexingProfileRegistry.java:80`
  (`asset.getResourceResolver()` borrowed) → no AEM-PERF-001/002, correctly.
- Sling Models: `HelloWorldModel` is statically bound in `helloworld.html` → no AEM-DEAD-003B.
- No `.jsp`, no `dialog.xml`, no orphaned config → AEM-CLOUD-002 / AEM-COMP-001 / AEM-DEAD-001 = 0.

---

## 6. Generic analyser fixes (no project-specific heuristics)

Ordered by value. Each one is a general modelling correction, not an allowlist entry for this repo.

**1. AEM-CLOUD-003 resolves mappings project-wide instead of per bundle (P0, false negatives).**
`AemCloud003Rule` collects `mappedSubservices` / `defaultMappedBundles` into flat `Set`s
(`AemCloud003Rule.java:112-125`, `160`) and then asks `defaultMappedBundles.isEmpty()` (line 141)
/ `mappedSubservices.contains(subservice)` (line 160). Service-user mappings are keyed by *bundle*,
so in any multi-bundle reactor one bundle's mapping hides every other bundle's unmapped call. It
happens to work here only because the reactor has exactly one bundle (`Bundles: 1`) — the
single-bundle sample-project shape. Fix: resolve the calling class's bundle symbolic name
(`bnd.bnd` / `<Bundle-SymbolicName>` instruction / pom `groupId.artifactId` fallback), and key both
maps by `(bundleId, subservice)` with `bundleId==null` for the default mapping; report
`UNMAPPED_DEFAULT_SUBSERVICE` when *this* bundle has no bundle-level entry, not when the project has
none. Add a two-bundle fixture (one mapped, one not) — today that fixture would produce 0 findings.

**2. AEM-CLOUD-001 has no notion of "default" or of runmode scope (P0, this repo's only ERROR FP).**
`AemCloud001Rule.isBenignValue()` treats any non-empty non-placeholder value as a secret. Fix:
(a) cross-reference the bound OCD's `@AttributeDefinition` **default** (and `@Component property`
defaults) and downgrade when the configured value equals the declared default — a restated default
leaks nothing, while a default that *looks* like a credential is the real defect; (b) flag
secret-keyed string defaults in Java as INFO ("credential-shaped default"), which is exactly what is
unflagged here; (c) apply runmode scope: `config.local` (and optionally `config.dev`) are not
deployed by Cloud Manager, so severity should be WARNING/INFO by default with a documented policy
switch, not ERROR. Same runmode policy also fixes the "ERROR on a dev-only credential" class of
findings generally (backlog F2/F8).

**3. AEM-SEC-001 cannot distinguish vendor boilerplate from project code (P1, 2 of 5 ERRORs).**
Fix generically: (a) for `data-sly-element`, treat the expression as an *element-name* sink — a
`context='unsafe'` there cannot inject markup or attributes, so cap it at WARNING; (b) recognise the
documented Core Components contract `item.element.name` over `page.htmlPageItems` (Page model) and
report it as INFO with a "vendor/archetype idiom" note; (c) when the file carries an Apache/Adobe
copyright header *and* the component's `.content.xml` declares `sling:resourceSuperType` to
`core/wcm` or `cq/experience-fragments`, mark the finding `vendor-generated` so a baseline can be
adopted without hand-maintaining per-file suppressions. Do not hardcode either component path.

**4. AEM-DUP-010 line numbers are wrong (P1, SARIF annotations land on the wrong line).**
Reported: `ScheduledPollingServiceImpl.java:214` and "matches … (lines 112-125)". Actual duplicated
token run: **216–227** and **114–127** (line 112 is
`HttpGet httpGet = buildGetRequest(callUrl, token, clientId, orgId);`, which cannot equal line 214's
`post.setEntity(new UrlEncodedFormEntity(params));`). Both marks are shifted −2. A synthetic probe
(`/private/var/.../T/aem-analyser-dup-probe`, two files with a 20-line common block) reports exact
lines, so the shift is content-dependent, not a constant offset — derive the region from the mark's
first `TokenEntry` position and add a golden fixture that asserts reported lines against the file's
actual text. Also fix the self-referential message (see §7).

**5. Subservice/rule regions start at the receiver, not the call (P2, highlight quality).**
`SubserviceCallExtractor` records `call.getRange().begin` (lines 73-74), i.e. the *scope* start:
for `resolverFactory.getServiceResourceResolver(null)` it reports column 42 (`resolverFactory`) with
a fixed 26-char span, underlining `resolverFactory.getServ`; the method name starts at column 58.
Fix: column = end of `call.getScope()` + 2, end column = that + `name.length()`, and do the same for
other rules that reuse call positions. Cheap, and it makes every SARIF/GitHub annotation point at
the thing being reported.

Secondary, worth a ticket each:

- **java:S1166 in test teardown.** Add an AEM/test-aware suppression in `SonarAemSuppressions`
  (which already handles S100/S1186/S1192): suppress S1166 when the catch variable is named
  `ignored`/`expected`/`_`, or when the catch is an empty body inside `@After*`/`finally` cleanup in
  a test source root (`src/test`, `it.tests`). Keep reporting real swallows in main code.
- **java:S1192 semantic role check.** Extend the existing `isJcrNodeLiteral` style suppression with a
  role check: when the literal is a map *key* for a different payload/domain than the constant's
  usage site, downgrade to INFO instead of recommending the constant (here: ES document field
  `status` vs HTTP response `status`).
- **AEM-DEAD-002A for platform PIDs with project suffixes.** `TableOfContentsFilter~<project>` and
  friends are exempt as platform PIDs, which is right for *ownership* but means a typo in an OOTB
  factory config can never be detected. Keep them out of DEAD-002A but add an INFO-level
  "unknown property on an OOTB PID" diagnostic when the dependency jars (`--dependency-jars`) can
  supply the metatype. This repo currently has no such typo (`additiv` is the real Sling name), but
  the class of defect is invisible today.
- **AEM-DEAD-004 and non-Java use-classes.** `xfpage/content.html` does
  `data-sly-use.body="body.js"` and no `body.js` exists in the component; nothing is reported.
  DEAD-004 appears to model only FQCN Java models. If relative script/use-class resolution is added,
  resolve through `sling:resourceSuperType` first and report INFO when unprovable — otherwise
  archetype files will become false positives.
- **Silent `target/` divergence.** The only mapping that ever existed for the service user lives in
  `ui.config/target/classes`, not in source. A one-line diagnostic ("build output contains N config
  files absent from source, ignored") would have explained the CLOUD-003 finding instantly (E11).

---

## 7. Wording changes to generalise

The rule messages contain **no project names** (grep for `wknd`, `we-retail`, `aem-sample`,
`archetype-aem-project`, `commerce-demo` in `aem-analyser-core/src/main` → 0 hits). The problems are
internal vocabulary leaking into user-facing text, and one invented identifier.

| Where | Current text | Problem | Suggested |
|---|---|---|---|
| `SonarBridgeComponents.java:251,292` | `Follow SonarJava rule S1166 (CatchUsesExceptionWithContextCheck) standards.` | Leaks the internal check class name; "standards" is meaningless filler. | Rule description in plain language + docs link, e.g. `Log the caught exception or rethrow it with the original cause: https://rules.sonarsource.com/java/RSPEC-1166` |
| `ConsoleReportFormatter.ruleTitle` | `SonarSource Java Quality (java:S1166)` | Not a title; the id is already printed next to it. `java:S1155` even loses its check name. | Use the Sonar rule title (`Exception handlers should preserve the original exceptions`), fall back to `SonarJava S1166`. |
| `AemCloud001Rule.java:74-75` | `Externalize secret via Adobe Cloud Manager secret variable: '$[secret:ELASTICSEARCHCONNECTIONSERVICEIMPL_ESPASSWORD]'` | Invents an env-var name from the implementation class simple name; assumes AEMaaCS secret variables even though the value may be a dev default or an AMS/on-prem deployment. | `Replace the literal with a placeholder (AEMaaCS: '$[secret:<NAME>]' declared in the Cloud Manager environment; otherwise '$[env:<NAME>]'). Do not use a value that also appears as an OCD default.` |
| `AemCloud003Rule.java:146-154` | `…no bundle-level 'user.mapping' entry exists in any ServiceUserMapperImpl.amended configuration.` | Correct, but the remediation omits the two other required steps and never names the bundle that must be mapped. | `Bundle '<bsn>' has no default service-user mapping. Add 'user.mapping': ['<bsn>=[<service-user>]'], create the user with repoinit ('create service user …') and grant read on the target path.` |
| `PmdCpdDuplicationEngine.java:125-127` | `Duplicate code block (14 lines, 77 tokens) in ScheduledPollingServiceImpl matches ScheduledPollingServiceImpl (lines 112-125).` | Self-referential and confusing when both marks are in one class; no method names. | `Duplicate code block (14 lines, 77 tokens) in ScheduledPollingServiceImpl#fetchAccessToken duplicates lines 112-125 of the same class (ScheduledPollingServiceImpl#pollJournalEvents).` |
| `ConsoleReportFormatter` (all violations) | `Target PID: ScheduledPollingServiceImpl` | For DUP-010/SEC-001 the value is a class or nothing at all — "PID" is OSGi-config vocabulary. | Emit only for config-bound rules; label `Component/Class:` otherwise, or omit. |
| `AemDead002ARule.java:107-109` | `Ghost configuration property 'disabled' in '….cfg.json' has no corresponding @AttributeDefinition in 'PID.Config'.` | File name *is* the PID, so the head and tail repeat the same identifier twice. | `Ghost configuration property 'disabled' in PID 'com.…ScheduledPollingServiceImpl' has no matching attribute in its OCD '…Config'.` |
| `ConsoleReportFormatter.java:159,157` | `BUILD FAILURE: 5 critical cross-boundary errors detected.` / `BUILD SUCCESS: no cross-boundary dead code detected.` | This is a scan, not a build; and an HTL XSS or a plaintext password is not "dead code" or "cross-boundary". | `5 error-severity violation(s) detected.` / `No violations detected.` |
| CLI error path (`Analysis failed: Analysis failed: No root pom.xml found under …`) | duplicated prefix | Wrapper and inner message both prefix. | Strip one prefix. |
| `AemSec001Rule.java:36` | `HTL expression uses '@ context=\'unsafe\'' which disables automatic XSS protection: <snippet>` | Escapes are awkward in console output (`\'`), and it does not say *which* sink. | `HTL output bypass: data-sly-element writes its value with context='unsafe'. Element names cannot inject attributes; review, or suppress if this is the Core Components htmlPageItems idiom.` (severity wording tied to fix 3) |

Not a wording issue, but adjacent: `--verbose` produced no output at all in a clean run, so a reader
cannot tell "verbose is a no-op here" from "verbose is broken". The baseline document claims this is
the expected healthy state; printing one line (`verbose: 0 diagnostics`) would make that explicit.

---

## 8. Verdict

Engine quality is good on this project: zero duplicate emissions, zero findings on platform PIDs,
zero resolver/session-leak FPs, zero unused-model FPs, and the previously missed service-user defect
is now correctly caught and is the only finding that should fail the gate today.

Remaining gate blockers are **analyser-side**, not project-side: one local-only default password
reported as an ERROR secret (CLOUD-001), and two Adobe archetype HTML lines reported as ERROR XSS
without any vendor/boilerplate notion (SEC-001). Fix items 1–3 above and the ERROR count on this
repo becomes 2 genuine `AEM-CLOUD-003` findings; fix items 1 and 2 also remove false negatives that
the current single-bundle, single-runmode sample projects cannot reveal.

### Reproduction notes

- Raw output: `/private/var/folders/2d/yvkjbkqx4hj__hy29qgrqtxw0000gp/T/aem-analyser-portfolio-triage/`
  (`aem-analyser.sarif`, `console.log`).
- DUP-010 offset probe: `/private/var/folders/2d/yvkjbkqx4hj__hy29qgrqtxw0000gp/T/aem-analyser-dup-probe{,2}/`
  (synthetic project + an isolated copy of `ScheduledPollingServiceImpl.java`; both outside the target repo).
- Target repo `git status` before and after: unchanged (39 entries: 9 modified, 30 untracked —
  identical before and after the scan; no file in the target tree was created, modified or removed).

---

## Post-fix status (generic hardening wave)

Applied from this triage:
- `AEM-CLOUD-003`: unresolved map variables are `UNRESOLVED` (not `NULL`); null-subservice reporting
  fails open when mapper configuration exists.
- `AEM-PERF-002`: adapted sessions from borrowed/injected/request resolvers are not leaks.
- `AEM-SEC-001`: `data-sly-element` unsafe contexts downgraded to WARNING.
- `AEM-CLOUD-001`: `config.local` downgraded to WARNING; remediation no longer invents a secret name.
- `AEM-DUP-010`: same-file overlapping windows and delegation-only blocks rejected; message includes
  both `path:line` ranges.
- Report wording: `Target:`, `GATE FAILED/PASSED`, no Sonar internal check names.

Result: portfolio 13 findings / **2 ERROR** (two `AEM-SEC-001` on Adobe Core Components boilerplate),
0 duplicate tuples. Enterprise project: 95 → 67 findings, deterministic across runs.
