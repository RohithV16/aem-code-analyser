#!/usr/bin/env python3
"""Verify that an analyser SARIF report proves every rule probe fired — both ways.

Checks, in order:
  1. every rule in the expected list produced at least one finding;
  2. every expected rule has at least one finding located in a probe fixture
     (so a rule firing only on real repo code cannot mask a broken probe);
  3. no rule outside the expected list is reported;
  4. the SARIF rule catalogue has no enabled entry missing from the expected list
     (disabled descriptors are retired rules or ones produced by another engine);
  5. (unless --allow-parse-errors) no file was silently skipped by the parser;
  6. with --clean/--evidence: every rule's declared positive probe produces **exactly one**
     finding and its clean twin produces **zero**. This is the negative control that turns
     "the rule fired" into "the rule is precise".

Exit codes: 0 = all checks pass, 1 = validation failure, 2 = usage/IO error.
"""
import json
import pathlib
import sys

# Any of these substrings identifies a path inside validation/probes.
# Java fixtures live in com/probe, content fixtures under apps/probe, config
# fixtures are named com.probe.*, dispatcher fixtures live under conf.d/ and
# content-package fixtures are the META-INF/vault/filter.xml files.
PROBE_PATH_MARKERS = ("com/probe/", "apps/probe/", "com.probe.", "conf.d/",
<<<<<<< HEAD
                      "META-INF/vault/filter.xml", "zzprobe")
=======
                      "META-INF/vault/filter.xml")
PAIR_OPTIONS = ("--clean", "--evidence", "--probes-root", "--clean-root")


def load_sarif(path: pathlib.Path) -> dict:
    try:
        document = json.loads(path.read_text())
        return {"document": document, "run": document["runs"][0]}
    except (json.JSONDecodeError, KeyError, IndexError) as exc:
        raise ValueError(f"Unreadable SARIF report {path}: {exc}") from exc


def parse_options(argv: list[str]) -> tuple[dict[str, str], list[str]]:
    """Parse the small option set without argparse, preserving the old positional API."""
    options: dict[str, str] = {}
    positional: list[str] = []
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg in PAIR_OPTIONS:
            if index + 1 >= len(argv):
                raise ValueError(f"{arg} needs a value")
            options[arg] = argv[index + 1]
            index += 2
            continue
        positional.append(arg)
        index += 1
    return options, positional


def uri_of(result: dict) -> str:
    locations = result.get("locations") or []
    if not locations:
        return ""
    return locations[0].get("physicalLocation", {}).get("artifactLocation", {}).get("uri", "")


def subtype_of(result: dict) -> str:
    return (result.get("properties") or {}).get("subtype") or ""


def line_of(result: dict) -> int:
    locations = result.get("locations") or []
    if not locations:
        return 0
    return int(locations[0].get("physicalLocation", {}).get("region", {}).get("startLine", 0) or 0)


def check_pairs(probes_run: dict, clean_run: dict, evidence_path: pathlib.Path,
                probes_root: pathlib.Path, clean_root: pathlib.Path,
                expected: list[str]) -> bool:
    try:
        evidence = json.loads(evidence_path.read_text())
        rows = {row["id"]: row for row in evidence["rules"]}
    except (json.JSONDecodeError, KeyError, TypeError) as exc:
        print(f"Unreadable evidence file {evidence_path}: {exc}", file=sys.stderr)
        return False

    positive_results = probes_run["results"]
    negative_results = clean_run["results"]
    failed = False
    for rule in expected:
        row = rows.get(rule)
        if row is None:
            print(f"PAIR FAIL {rule}: no entry in {evidence_path.name}", file=sys.stderr)
            failed = True
            continue
        positive = row.get("positiveProbe") or {}
        negative = row.get("negativeProbe") or {}
        problems = []
        if not positive.get("path"):
            problems.append("positiveProbe.path is missing")
        if not negative.get("path"):
            problems.append("negativeProbe.path is missing")
        if problems:
            print(f"PAIR FAIL {rule}: " + "; ".join(problems), file=sys.stderr)
            failed = True
            continue

        probe_file = probes_root / positive["path"]
        twin_file = clean_root / negative["path"]
        if not probe_file.is_file():
            problems.append(f"positive probe does not exist: {positive['path']}")
        if not twin_file.is_file():
            problems.append(f"clean twin does not exist: {negative['path']}")

        want_line = int(positive.get("line") or 0)
        want_subtype = (positive.get("subtype") or "").upper()
        matches = [
            result for result in positive_results
            if result.get("ruleId") == rule
            and uri_of(result) == positive["path"]
            and line_of(result) == want_line
            and (not want_subtype or subtype_of(result).upper() == want_subtype)
        ]
        if len(matches) != 1:
            problems.append(
                f"positive probe produced {len(matches)} finding(s), expected exactly 1 "
                f"({positive['path']}:{want_line}"
                + (f" {want_subtype}" if want_subtype else "") + ")")

        negatives = [
            result for result in negative_results
            if result.get("ruleId") == rule and uri_of(result) == negative["path"]
        ]
        if negatives:
            problems.append(
                "clean twin produced %d finding(s), expected 0: %s"
                % (len(negatives),
                   ", ".join(sorted({f"L{line_of(r)} {subtype_of(r)}" for r in negatives}))))

        if problems:
            for problem in problems:
                print(f"PAIR FAIL {rule}: {problem}", file=sys.stderr)
            failed = True
        else:
            print(f"PAIR OK   {rule}  positive={positive['path']}:{want_line} "
                  f"negative={negative['path']}")

    extra = sorted(set(rows) - set(expected))
    if extra:
        print("Evidence rows for rules absent from expected-rules.txt: " + ", ".join(extra),
              file=sys.stderr)
        failed = True
    return not failed
>>>>>>> feat/w3-tooling


def main() -> int:
    try:
        options, positional = parse_options(sys.argv[1:])
    except ValueError as exc:
        print(exc, file=sys.stderr)
        return 2
    positional = [arg for arg in positional if not arg.startswith("--")]
    if len(positional) < 2:
        print("usage: check_rule_probes.py <sarif> <expected-rules.txt> "
              "[--allow-parse-errors] [--clean <sarif> --evidence <json> "
              "--probes-root <dir> --clean-root <dir>]", file=sys.stderr)
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
        probes = load_sarif(sarif_path)
    except ValueError as exc:
        print(exc, file=sys.stderr)
        return 2
    run = probes["run"]
    results = run["results"]

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
        locations_by_rule.setdefault(rule, []).append(uri_of(result))

    def probes_for(uris):
        return [u for u in uris if any(marker in u for marker in PROBE_PATH_MARKERS)]

    missing = []
    not_on_probe = []
    for rule in expected:
        uris = locations_by_rule.get(rule, [])
        if not uris:
            missing.append(rule)
        elif not probes_for(uris):
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
        probe_uris = probes_for(uris)
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

    if any(key in options for key in PAIR_OPTIONS):
        missing_options = [key for key in PAIR_OPTIONS if key not in options]
        if missing_options:
            print("Pair checking needs " + ", ".join(PAIR_OPTIONS) + " together", file=sys.stderr)
            return 2
        clean_path = pathlib.Path(options["--clean"])
        if not clean_path.is_file():
            print(f"Clean-twin SARIF report not found: {clean_path}", file=sys.stderr)
            return 2
        try:
            clean = load_sarif(clean_path)
        except ValueError as exc:
            print(exc, file=sys.stderr)
            return 2
        clean_notifications = (clean["run"].get("invocations") or [{}])[0].get(
            "toolExecutionNotifications", [])
        clean_parse_errors = [n for n in clean_notifications
                              if "PARSE_ERROR" in n.get("message", {}).get("text", "")]
        if clean_parse_errors and fail_on_parse_errors:
            print(f"{len(clean_parse_errors)} file(s) skipped by the parser in the clean twin: "
                  + ", ".join(sorted({n.get('message', {}).get('text', '')
                                      for n in clean_parse_errors})[:5]), file=sys.stderr)
            return 1
        if not check_pairs(probes["run"], clean["run"], pathlib.Path(options["--evidence"]),
                           pathlib.Path(options["--probes-root"]),
                           pathlib.Path(options["--clean-root"]), expected):
            return 1

    print(f"\nAll {len(expected)} rule probes detected on probe fixtures.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
