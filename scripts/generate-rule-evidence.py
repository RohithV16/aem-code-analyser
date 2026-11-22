#!/usr/bin/env python3
"""Generate the committed rule-evidence.json from the probe manifest.

Sources of truth:
  * validation/probes/evidence.json — which file/line/subtype is the positive probe and which
    clean twin proves the rule is silent;
  * RuleId.java — status, since and replacedBy for every id, including the retired one that has
    no probe.

The same bytes are written to the core jar resource (`--list-rules --format json` reads it) and
to `docs/evidence/` (the published copy). Run with --check in CI to fail on drift.
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
EVIDENCE = ROOT / "validation/probes/evidence.json"
RULE_ID = ROOT / "aem-analyser-core/src/main/java/io/github/rohithv16/aem/analyser/core/model/report/RuleId.java"
RESOURCE = ROOT / "aem-analyser-core/src/main/resources/rule-evidence.json"
PUBLISHED = ROOT / "docs/evidence/rule-evidence.json"

# Rules introduced after the 1.0 line; mirrors RuleId.introducedIn (the lock test pins the value).
NEW_IN_1_1 = {"AEM-COMP-002", "AEM-QUAL-008", "AEM-DEAD-007", "AEM-CLOUD-006", "AEM-CLOUD-007"}


def rule_ids() -> dict[str, dict]:
    source = RULE_ID.read_text()
    rows: dict[str, dict] = {}
    for match in re.finditer(r'^\s{4}([A-Z0-9_]+)\("([^"]+)"', source, re.M):
        name, rule_id = match.group(1), match.group(2)
        # The constant ends at the next '),' at the constant's indentation; a retired constant
        # carries RuleStatus.RETIRED and its replacement string inside that span.
        end = source.find("),", match.start())
        block = source[match.start():] if end < 0 else source[match.start():end]
        retired = "RuleStatus.RETIRED" in block
        replacement = None
        if retired:
            replacement_match = re.search(r'"(AEM-[A-Z0-9-]+)"\s*\)?\s*$', block.strip())
            if replacement_match:
                replacement = replacement_match.group(1)
        rows[rule_id] = {
            "status": "retired" if retired else "active",
            "since": "1.1.0" if rule_id in NEW_IN_1_1 else "1.0.0",
            "replacedBy": replacement,
        }
    return rows


def build() -> dict:
    probes = json.loads(EVIDENCE.read_text())
    by_rule = {row["id"]: row for row in probes["rules"]}
    ids = rule_ids()
    output = {
        "schemaVersion": 1,
        "description": "Lifecycle and two-sided probe evidence for every rule id.",
        "rules": [],
    }
    for rule_id in sorted(ids):
        lifecycle = ids[rule_id]
        probe = by_rule.get(rule_id)
        positive = (probe or {}).get("positiveProbe") or {}
        negative = (probe or {}).get("negativeProbe") or {}
        output["rules"].append({
            "id": rule_id,
            "docAnchor": rule_id.lower(),
            "status": lifecycle["status"],
            "since": lifecycle["since"],
            "replacedBy": lifecycle["replacedBy"],
            "positiveProbe": positive.get("path"),
            "positiveLine": positive.get("line"),
            "positiveSubtype": positive.get("subtype"),
            "negativeProbe": negative.get("path"),
        })
    return output


def render() -> str:
    return json.dumps(build(), indent=2) + "\n"


def main() -> int:
    check = "--check" in sys.argv[1:]
    content = render()
    if check:
        problems = []
        for path in (RESOURCE, PUBLISHED):
            if not path.is_file():
                problems.append(f"missing {path.relative_to(ROOT)}")
            elif path.read_text() != content:
                problems.append(f"{path.relative_to(ROOT)} is stale: run scripts/generate-rule-evidence.py")
        if problems:
            print("\n".join(problems), file=sys.stderr)
            return 1
        print(f"rule-evidence.json is up to date ({len(build()['rules'])} rules)")
        return 0
    for path in (RESOURCE, PUBLISHED):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        print(f"wrote {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
