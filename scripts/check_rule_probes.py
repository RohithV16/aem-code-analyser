#!/usr/bin/env python3
"""Verify that an analyser SARIF report proves every rule probe fired.

Checks, in order:
  1. every rule in the expected list produced at least one finding;
  2. every expected rule has at least one finding located in a probe fixture
     (so a rule firing only on real repo code cannot mask a broken probe);
  3. no rule outside the expected list is reported;
  4. the SARIF rule catalogue has no enabled entry missing from the expected list
     (disabled descriptors are retired rules or ones produced by another engine);
  5. (unless --allow-parse-errors) no file was silently skipped by the parser.

Exit codes: 0 = all checks pass, 1 = validation failure, 2 = usage/IO error.
"""
import json
import sys
import pathlib

# Any of these substrings identifies a path inside validation/probes.
# Java fixtures live in com/probe, content fixtures under apps/probe, and
# config fixtures are named com.probe.*.
PROBE_PATH_MARKERS = ("com/probe/", "apps/probe/", "com.probe.")


def main() -> int:
    positional = [arg for arg in sys.argv[1:] if not arg.startswith("--")]
    if len(positional) < 2:
        print("usage: check_rule_probes.py <sarif> <expected-rules.txt> [--allow-parse-errors]",
              file=sys.stderr)
        return 2

    sarif_path = pathlib.Path(positional[0])
    expected_path = pathlib.Path(positional[1])
    # A deliberately broken real repository may contain unparseable files. The fixture-only run
    # must not, so parse diagnostics are fatal there and reported-only during injection.
    fail_on_parse_errors = "--allow-parse-errors" not in sys.argv[1:]

    if not sarif_path.is_file():
        print(f"SARIF report not found: {sarif_path}", file=sys.stderr)
        return 2

    try:
        document = json.loads(sarif_path.read_text())
        run = document["runs"][0]
        results = run["results"]
    except (json.JSONDecodeError, KeyError, IndexError) as exc:
        print(f"Unreadable SARIF report {sarif_path}: {exc}", file=sys.stderr)
        return 2

    expected = [line.strip() for line in expected_path.read_text().splitlines() if line.strip()]
    if not expected:
        print(f"No expected rules listed in {expected_path}", file=sys.stderr)
        return 2

    locations_by_rule: dict[str, list[str]] = {}
    unlocated = 0
    for result in results:
        rule = result.get("ruleId", "")
        locations = result.get("locations") or []
        if not locations:
            unlocated += 1
            locations_by_rule.setdefault(rule, [])
            continue
        uri = locations[0].get("physicalLocation", {}).get("artifactLocation", {}).get("uri", "")
        locations_by_rule.setdefault(rule, []).append(uri)

    def probes(uris):
        return [u for u in uris if any(marker in u for marker in PROBE_PATH_MARKERS)]

    missing = []
    not_on_probe = []
    for rule in expected:
        uris = locations_by_rule.get(rule, [])
        if not uris:
            missing.append(rule)
        elif not probes(uris):
            not_on_probe.append(rule)

    unexpected = sorted(rule for rule in locations_by_rule if rule and rule not in expected)

    # The SARIF driver declares the complete rule catalogue. Comparing it with the expected list
    # keeps the probe matrix honest when a new rule is added without a probe.
    # A descriptor with defaultConfiguration.enabled == false has no evaluator: it is retired or
    # produced by another engine (PMD CPD), so it can never fire and needs no probe.
    declared = [
        rule.get("id", "")
        for rule in run.get("tool", {}).get("driver", {}).get("rules", [])
        if (rule.get("defaultConfiguration") or {}).get("enabled", True)
    ]
    catalogue_gaps = sorted(rule for rule in declared if rule and rule not in expected)
    orphaned = sorted(rule for rule in expected if rule not in declared)

    invocations = run.get("invocations") or []
    notifications = invocations[0].get("toolExecutionNotifications", []) if invocations else []
    parse_diagnostics = [
        note for note in notifications
        if "PARSE_ERROR" in note.get("message", {}).get("text", "")
    ]

    total = max(len(expected), len(declared))
    ok_count = len(expected) - len(missing) - len(not_on_probe)
    print(f"Probe rules verified: {ok_count}/{total}")
    for rule in expected:
        uris = locations_by_rule.get(rule, [])
        probe_uris = probes(uris)
        if not uris:
            marker, detail = "FAIL", "no finding"
        elif not probe_uris:
            marker, detail = "FAIL", "fired only on non-probe code: " + ", ".join(sorted(set(uris))[:3])
        else:
            marker = "PASS"
            detail = ", ".join(sorted(set(probe_uris))[:3])
        print(f"  [{marker}] {rule}  ({detail})")

    failed = False
    if missing:
        print("\nRules with no finding at all: " + ", ".join(missing), file=sys.stderr)
        failed = True
    if not_on_probe:
        print("Rules not demonstrated by a probe fixture: " + ", ".join(not_on_probe), file=sys.stderr)
        failed = True
    if unexpected:
        print("Rules outside expected-rules.txt (add probes or remove the rule): "
              + ", ".join(unexpected), file=sys.stderr)
        failed = True
    if catalogue_gaps:
        print("Rule catalogue entries without a probe in expected-rules.txt: "
              + ", ".join(catalogue_gaps), file=sys.stderr)
        failed = True
    if orphaned:
        print("Expected rules the analyser does not declare: " + ", ".join(orphaned), file=sys.stderr)
        failed = True
    if unlocated:
        print(f"{unlocated} result(s) had no location", file=sys.stderr)
        failed = True
    if parse_diagnostics:
        message = (f"{len(parse_diagnostics)} file(s) skipped by the parser: "
                   + ", ".join(sorted({n.get('message', {}).get('text', '') for n in parse_diagnostics})[:5]))
        if fail_on_parse_errors:
            print(message, file=sys.stderr)
            failed = True
        else:
            print("Note: " + message, file=sys.stderr)
    if failed:
        return 1

    print(f"\nAll {len(expected)} rule probes detected on probe fixtures.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
