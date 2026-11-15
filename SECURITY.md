# Security Policy

## Supported versions

Security fixes are applied to the latest released `1.x` line. Snapshot builds are not supported.

## Reporting a vulnerability

Please do **not** open a public issue for a security problem. Report it privately to the maintainer
listed in the repository profile, including:

- the affected module and version,
- a minimal reproduction (project layout or fixture),
- the observed impact (e.g. arbitrary file read, XXE, command execution).

You will receive an acknowledgement within a few business days. Once a fix is available, the issue
and credit will be disclosed in `CHANGELOG.md`.

## Scope and threat model

The analyser reads untrusted project files. Relevant hardening already in place:

- OSGi configuration files are size-capped (10 MiB) and malformed inputs become diagnostics, not
  crashes.
- `.content.xml` handling must not resolve external entities when the StAX rewrite lands; until then
  the scanner performs string matching only.
- The auto-fixer edits Java sources; `--fix-dry-run` previews changes and fixes are limited to a known
  set of deterministic rules.
- The Maven plugin and CLI never execute project code. `--collect-coverage` is the only path that runs
  a build, and it is opt-in.
- Baseline and inline suppressions are applied before export and before the exit code is computed.
