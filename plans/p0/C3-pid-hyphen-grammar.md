# C3 — PID grammar rejects hyphens

Status: Done (wave 1, 2026-11-15)
Priority: P0
Area: aem-analyser-core / `parser.config.ConfigFilePidResolver`

Verified evidence: `PID_GRAMMAR` used `[a-zA-Z0-9_]+` per segment, so a PID such as
`my-service.pid` failed to match and `OsgiConfigScanner` silently dropped the file. Hyphens in a
later segment only worked by accident when the regex interpreted them as a legacy factory delimiter.

Problem: legitimate hyphenated OSGi PIDs were invisible to every rule.

Desired behavior: hyphens are legal PID characters; only `~` (both formats) and `-` (legacy `.config`)
act as factory delimiters, with the longest valid PID preferred.

Files to change:
- `aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/parser/config/ConfigFilePidResolver.java`

Implementation steps (landed):
1. Replaced the single regex with a boundary-safe `PID_PATTERN` that allows `[a-zA-Z0-9_-]` segments.
2. Resolve `~` first (RFC 724 factory) for both formats.
3. For legacy `.config`, split on the **last** hyphen whose prefix is a valid PID; flag
   `ambiguousHyphen` unless the suffix is purely numeric.
4. Validate factory subnames and return an invalid result for single-word names.

Tests to add (landed):
- `ConfigFilePidResolverTest` — hyphenated leading segment; `~` factory with hyphenated PID; legacy
  `my-service.pid-instance`; numeric legacy suffix not ambiguous.

Acceptance criteria: all cases above resolve; single-word names remain rejected.

Risks / notes: `ambiguousHyphen` is now populated more often; C8 consumes it.
