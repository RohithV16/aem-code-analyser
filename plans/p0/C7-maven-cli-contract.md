# C7 — CLI/Maven docs advertised parameters that did not exist

Status: Done (wave 1, 2026-11-15)
Priority: P0
Area: `README.md`, aem-analyser-maven-plugin, aem-analyser-cli

Verified evidence:
- README documented CLI `--output-file`; the actual option is `-o/--output <directory>`.
- README documented Maven `<format>` and `<outputFile>` on goal `check`; `CheckMojo` had neither.
- `ReportMojo` and the CLI called `ReportFormat.valueOf(...)` unguarded, leaking a raw
  `IllegalArgumentException`.

Problem: users copying the documented configuration got "Unknown parameter" or a stack trace.

Desired behavior: documented options work; invalid formats fail with an actionable message and exit
code 2 / `MojoFailureException`.

Files to change:
- `aem-analyser-maven-plugin/.../AbstractAnalyserMojo.java` (new) — shared parameters and export logic.
- `aem-analyser-maven-plugin/.../CheckMojo.java`, `ReportMojo.java` — extend the base.
- `aem-analyser-cli/.../AnalyserCli.java` — `parseFormats()` + `IllegalArgumentException` handling.
- `README.md` — corrected CLI examples and Maven `<formats>`/`<outputFile>`.

Implementation steps (landed):
1. `AbstractAnalyserMojo` owns `formats`, `outputFile`, profiles, threads, ignore lists, Sonar toggles
   and writes reports before the fail gate.
2. Single format + `outputFile` writes exactly that file; multiple formats treat it as a directory.
3. CLI parses formats once into `List<ReportFormat>`; invalid values print supported formats and exit 2.
4. README updated to `--output <dir>`, `<formats>`, `<outputFile>` and `--ignore-config`.

Tests to add:
- Maven Invoker ITs (E13) for the documented block and an invalid format.
- CLI unit test for invalid `--format` → exit 2 (E12).

Acceptance criteria: documented commands run; invalid format yields a clean error.

Risks / notes: keep a singular `<format>` alias if users already adopted the old README (E13).
