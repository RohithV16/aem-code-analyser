# C9 — Missing `LICENSE` despite the README badge

Status: Done (wave 1, 2026-11-15)
Priority: P0
Area: repository root, `pom.xml`

Verified evidence: README links the Apache-2.0 badge to `LICENSE`, but no `LICENSE` file existed and
the root POM had no `<licenses>` block.

Problem: legally ambiguous distribution and a broken Maven Central checklist.

Desired behavior: Apache-2.0 text at the root and machine-readable license metadata for Central.

Files to change (landed):
- `LICENSE` (new) — full Apache License 2.0 text with the project copyright line.
- `pom.xml` — `<inceptionYear>` and `<licenses>` block.

Implementation steps:
1. Added the canonical Apache-2.0 text.
2. Declared `Apache License, Version 2.0` in the root POM so all modules inherit it.

Tests to add:
- Release checklist item in `docs/MAVEN_CENTRAL_PUBLISHING_GUIDE.md` (O6/O8).

Acceptance criteria: `ls LICENSE` succeeds; `mvn help:effective-pom` shows the license in every module.

Risks / notes: add `<developers>`/`<scm>` before the first Central release (O6).
