# E7 — OSGi config parser fidelity

Status: Verified
Priority: P1
Area: aem-analyser-core / `parser.config` (`CfgJsonConfigParser`, `FelixConfigParser`,
`PropertiesConfigParser`, `XmlOsgiConfigParser`, `ConfigKeyParser`, `OsgiConfigParser`)

Problem / opportunity:
Wave-2 status (2026-11-15) — **parser hardening implemented**:
- `.content.xml` extension stripping is shared with the scanner (C11).
- UTF-8 BOM is consumed before Jackson parsing.
- Duplicate JSON keys keep the first occurrence (stable location).
- Nested arrays/objects now round-trip through Jackson (`JsonNode.toString()`), preserving field names.
- Remaining E7 work: duplicate-key diagnostics, `.content.xml` StAX rewrite, runmode merge model,
  `.cfg` line/column tracking.

Already handled (verified — do not re-implement):
- `CfgJsonConfigParser` tolerates Java/YAML comments, trailing commas, unquoted field names and
  single quotes; tracks key line/column; strips type hints; records env placeholders.
- `ConfigKeyParser` keeps `:`-prefixed metadata keys intact and extracts `;default=` values that
  themselves contain colons (existing test).
- `FelixConfigParser` handles `I"…"`, `L"…"`, `F/D/B/S/X/T"…"`, escapes, arrays, comments and
  backslash line continuations.
- `AemDup002Rule` groups by the exact runmode set, so `config/` and `config.author/` are not
  reported as duplicates of each other.
Real gaps:
1. **`.content.xml` PID suffix**: `OsgiConfigParser.stripExtension` (l.52-56) strips only `.xml`, so
   `a.b.C.content.xml` gets PID `a.b.C.content`. (Shared fix with E1 step 1.)
2. **Duplicate JSON keys** are silently overwritten (`CfgJsonConfigParser.parse` l.51 `put`). Aether
   deployments are last-wins, but the analyser should surface a diagnostic and keep the first value's
   location stable.
3. **BOM / encoding**: `Files.newBufferedReader(file)` keeps a leading U+FEFF, which Jackson rejects
   with "Unexpected character" — the whole file is then skipped. Also `Files.readString` in the other
   parsers throws on non-UTF-8 bytes.
4. **Nested arrays/objects**: `readRawArrayOrObject` never emits `FIELD_NAME` tokens, so
   `{"o":{"a":1}}` is serialised as `{null,1}`; backslash escaping is also missing from the string
   branch.
5. **`.content.xml`**: regex-based attribute scan ignores element structure, requires double quotes,
   keeps `jcr:mixinTypes` / `sling:resourceType` as configuration properties, and marks every value
   `DataType.STRING` (so `[a,b]` multi-value props are not arrays).
6. **Runmode precedence/merge** does not exist: two files for the same PID under `config` and
   `config.author` are independent nodes with no "effective configuration" model. Needed by 002A and
   by F8, not by 002.
7. `.cfg` (`PropertiesConfigParser`) loses line/column (always 1,1) and ISO-8859-1 decoding is
   assumed by `Properties.load(InputStream)`.

Desired behavior:
- Every format yields the same base PID for the same logical file.
- Malformed input produces a diagnostic, never a silent drop that changes rule results.
- Values round-trip faithfully enough for key-level rules and for F2 allowlists.

Files to change:
- `core/parser/config/OsgiConfigParser.java` — shared `stripExtension`.
- `core/parser/config/CfgJsonConfigParser.java` — BOM strip, duplicate-key diagnostic, JsonNode-based
  value capture (`ObjectMapper.readTree`) instead of manual token walking.
- `core/parser/config/XmlOsgiConfigParser.java` — switch to a streaming parser (`XMLStreamReader`)
  with element-depth 1 attribute capture and an explicit ignored-attribute set.
- `core/parser/config/PropertiesConfigParser.java` — read as UTF-8 with a line-index pass for
  line/column; support `\uXXXX` (already via `Properties`).
- `core/parser/config/OsgiConfigDefinition.java` — optional `effectiveProperties` view for merge
  (F8), not required for E7 acceptance.

Implementation steps:
1. Add `ConfigFilePidResolver.stripExtension(String fileName)` and call it from scanner and parser.
2. In `CfgJsonConfigParser`, open a `Reader` that skips a leading `\uFEFF`, and detect duplicates via
   `properties.containsKey(baseKey)`; emit `ConfigParseDiagnostic(DUPLICATE_KEY, rawKey, line, col)`.
3. Keep `ConfigPropertyEntry` construction but feed `rawValue` from `JsonNode.toString()`.
4. Replace the XML regex with `XMLInputFactory` (disable DTD/external entities) and capture attributes
   of the root element only; add `jcr:mixinTypes`, `jcr:created*`, `sling:resourceType`,
   `cq:template` to an ignore set.
5. Add a `ConfigMerge` helper that, given all definitions for one PID, returns the property map for a
   requested runmode set (default < `config.author` < `config.author.dev` ordering). Do not wire it
   into rules yet.

Tests to add:
- Parameterized parser tests per format with a shared assertion helper (`baseKey -> value/type`).
- `CfgJsonConfigParserTest`: BOM file; duplicate key; nested object/array fidelity; `:` metadata keys.
- `XmlOsgiConfigParserTest`: single quotes, multi-valued `[a,b]`, `jcr:mixinTypes` ignored,
  `.content.xml` PID equals scanner PID.
- `FelixConfigParserTest`: `["a","b"]`, empty array, `X"…"` byte array.
- `ConfigMergeTest`: precedence order.

Acceptance criteria:
- A `.content.xml` config is not reported as `ORPHANED_CONFIGURATION` and its PID matches the
  component PID.
- A BOM-prefixed `.cfg.json` is parsed, not skipped.
- Nested value fidelity is asserted by a round-trip test.
- No parser throws on malformed input when running through `DefaultAemCodeAnalyser` (diagnostic only).

Risks / notes:
- Switching XML to StAX overlaps O3 (streaming for large files); implement here, keep the
  max-file-size guard in O3.
- Changing duplicate-key handling can change which location is reported; keep the *first* occurrence
  for stability with baselines (F1).
