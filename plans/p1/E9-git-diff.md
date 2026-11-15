# E9 — GitDiffResolver path and range correctness

Status: Done
Priority: P1
Area: aem-analyser-core / `git.GitDiffResolver`, `cli.AnalyserCli` (`--changed-only`, `--since`)

Problem / opportunity:
Already handled (verified — do not re-implement):
- Non-git directories and git failures return `isGitRepo=false`, and `matches()` then keeps every
  violation (fail-open by design).
- Deleted files (`D` status) are not added to `changedFiles`.
- Hunk ranges are expanded to individual line numbers; whole-file/unknown-line changes match.
- Path matching is suffix-based in both directions, which absorbs most subdirectory-invocation
  differences.
Real gaps:
1. **Renames**: porcelain prints `R  old -> new`; `file = line.substring(3).trim()` becomes
   `old -> new`, which matches nothing.
2. **Untracked directories**: porcelain collapses them to `?? dir/`. The trailing slash never matches
   a file path, so *all* violations in a brand-new directory are dropped — the worst false negative
   in `--changed-only`.
3. **Quoting**: git quotes paths containing spaces/unicode by default (`core.quotePath=true`), so the
   recorded name contains literal `"` and `\ooo` escapes.
4. **`--since` semantics**: `git diff -U0 <ref>` is the two-dot form (ref vs worktree). PR gating
   usually wants `<ref>...HEAD` (merge-base). Also uncommitted changes are included twice.
5. **stderr merged into stdout** (`redirectErrorStream(true)`), so `fatal: …` lines can be parsed as
   file names.
6. `.trim()` on paths destroys names with a legitimate trailing space.
7. `matches()` does an O(files × changedFiles) scan per violation and ignores `changedLines` order.

Desired behavior:
- Changed files are exact repo-relative paths, rename-aware, unquoted, directory-expanded.
- `--since` compares against the merge base by default, with an opt-out for worktree diffs.

Files to change:
- `core/git/GitDiffResolver.java` — single `git` invocation strategy, NUL-separated output,
  `-c core.quotepath=false`, `--no-renames` or explicit rename parsing, stderr to `DISCARD`.
- `cli/AnalyserCli.java` — validate `--since` with `--changed-only` interaction; print the resolved
  mode.

Implementation steps:
1. Run `git -c core.quotepath=false status --porcelain=v1 -z --untracked-files=all` and parse the NUL
   stream (handles spaces, unicode and renames: `R  new\0old\0`).
2. `pb.redirectErrorStream(false)`; log stderr at debug only.
3. Expand untracked entries that end with `/` by walking the directory for the relevant extensions
   (`.java`, `.xml`, `.json`, `.config`, `.cfg`, `.html`).
4. Diff mode: default `git diff -U0 <ref>...HEAD` plus `git diff -U0 HEAD` for the worktree; add a
   `--since-mode {merge-base,ref,worktree}` option, default `merge-base`.
5. Replace the linear `changedFiles.stream().anyMatch` with a `Set` of exact paths plus a
   prefix set for directory entries; keep the legacy suffix behaviour as a fallback only.

Tests to add:
- `GitDiffResolverTest` with a real temporary repo (`git init`, `git -c user.email=… commit`):
  rename, path with spaces, path with unicode, new untracked directory, deleted file, `--since` with
  two commits, invocation from a subdirectory.
- Keep the non-git fallback test; replace `testCurrentGitRepositoryResolution` (depends on this
  repo's own state) with a fixture repo.

Acceptance criteria:
- A brand-new directory of sources produces violations under `--changed-only`.
- A rename produces exactly one entry that matches the new path.
- No git stderr line ever appears in `changedFiles`.

Risks / notes:
- Porcelain v1 `-z` output is stable across git versions; do not use `--porcelain=v2` unless needed.
- `--changed-only` filters by `region().startLine()`; whole-file violations use line 1, so they only
  survive when line 1 changed. That is a pre-existing semantic — document it in the CLI help.

---

## Implementation

- `git status --porcelain=v1 -z --untracked-files=all` with `core.quotepath=false`: renames,
  spaces/unicode and untracked directories are parsed exactly; stderr is discarded.
- `--since <ref>` uses `<ref>...HEAD` (merge base) plus a second `git diff HEAD` for the worktree.
- `matches()` prefers exact/suffix segment matching and no longer trims legitimate trailing spaces.
- Tests: temporary git repo covering rename, untracked directory and a path with a space.
