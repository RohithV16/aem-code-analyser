# C11 — `.content.xml` PIDs carried a bogus `.content` suffix

Status: Done (wave 1, 2026-11-15)
Priority: P0
Area: aem-analyser-core / `parser.config.OsgiConfigParser`, `discovery.OsgiConfigScanner`,
`parser.config.ConfigFilePidResolver`

Verified evidence: `OsgiConfigParser.stripExtension` stripped only the last dotted segment for
non-`.cfg.json` files, so `com.example.Foo.content.xml` produced the PID `com.example.Foo.content`.
`OsgiConfigScanner` already stripped `.content.xml` correctly, so discovery and parsing disagreed and
`AEM-DEAD-001` could report a phantom orphan.

Problem: the same file had two different PIDs depending on which layer resolved it.

Desired behavior: one shared extension stripper for all layers, longest extension first
(`.cfg.json`, `.content.xml`, `.config`, `.cfg`).

Files to change (landed):
- `ConfigFilePidResolver.stripExtension(String)` — new public helper.
- `OsgiConfigParser` — uses the shared helper.
- `OsgiConfigScanner` — delegates to the shared helper.

Tests to add (landed):
- `ConfigFilePidResolverTest.stripExtensionHandlesEverySupportedFormat`.

Acceptance criteria: parser and scanner agree for every supported extension.

Risks / notes: `.content.xml` files must still pass the `sling:OsgiConfig` content check in the
scanner.
