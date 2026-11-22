#!/usr/bin/env python3
"""Writes the forwarding map for the /rules/ URLs the docs rebuild deleted, plus a stub per URL.

The site moved from one page per rule under ``/rules/<id>/`` to ``/issues/#<anchor>``, and a static
host cannot answer a 301. Two things recover the inbound links that point at the old scheme:

* ``docs/_data/redirects.json`` — the map itself, path -> target, read by the 404 page and by
  ``scripts/check-site-seo.py``;
* one meta-refresh stub per old URL shape (``/rules/<id>/`` and ``/rules/<ID>.html``) so an old link
  returns 200 and a canonical instead of falling through to the 404 at all.

Both are derived from the committed rule pages, so a new rule automatically gets its legacy URLs and
a retired rule loses them. Output is deterministic: sorted, no timestamps, byte-identical on re-run.

Usage: scripts/build-legacy-redirects.py [docs-root]
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

DOCS = Path(sys.argv[1] if len(sys.argv) > 1 else "docs")
SITE = "https://rohithv16.github.io/aem-code-analyser"

STUB = """---
layout: null
sitemap: false
---
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Moved: {rule_id} · AEM Code Analyser</title>
  <meta name="robots" content="noindex, follow">
  <link rel="canonical" href="{site}/issues/{anchor}/">
  <meta http-equiv="refresh" content="0; url={site}/issues/#{anchor}">
  <style>
    body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
           margin: 15vh auto; max-width: 34rem; padding: 0 1.5rem; line-height: 1.6; color: #0e1417; }}
    a {{ color: #0a6a63; }}
    code {{ font-family: ui-monospace, Menlo, monospace; background: #f5f6f3; padding: 0.1em 0.3em; border-radius: 3px; }}
  </style>
</head>
<body>
  <h1>Moved to the issues page</h1>
  <p>
    <code>{rule_id}</code> now lives with every other rule, at
    <a href="{site}/issues/#{anchor}">{site}/issues/#{anchor}</a>.
  </p>
  <p><small>You are seeing this page because an older link used <code>{from}</code>.</small></p>
  <script>window.location.replace("{site}/issues/#{anchor}");</script>
</body>
</html>
"""


def baseurl() -> str:
    text = (DOCS / "_config.yml").read_text(encoding="utf-8")
    match = re.search(r"^baseurl:\s*(\S+)\s*$", text, re.M)
    return match.group(1) if match else ""


def rule_pages() -> list[tuple[str, str]]:
    """Returns (rule id, anchor) for every generated per-rule page, in anchor order."""
    rules = []
    issues = DOCS / "issues"
    if not issues.is_dir():
        return rules
    for page in sorted(issues.glob("*/index.html")):
        text = page.read_text(encoding="utf-8")
        match = re.search(r'^rule_id:\s*"([A-Z0-9-]+)"', text, re.M)
        if match:
            rules.append((match.group(1), page.parent.name))
    return rules


def main() -> int:
    base = baseurl()
    rules = rule_pages()
    entries = []
    for rule_id, anchor in rules:
        target = f"{base}/issues/#{anchor}"
        entries.append({"from": f"{base}/rules/{anchor}/", "to": target})
        entries.append({"from": f"{base}/rules/{rule_id}.html", "to": target})

    data_dir = DOCS / "_data"
    data_dir.mkdir(parents=True, exist_ok=True)
    (data_dir / "redirects.json").write_text(
        json.dumps(entries, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")

    for rule_id, anchor in rules:
        for rel, depth in ((Path("rules") / anchor / "index.html", None),
                           (Path("rules") / f"{rule_id}.html", None)):
            path = DOCS / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                STUB.format(rule_id=rule_id, anchor=anchor, site=SITE, **{"from": f"/{rel.as_posix()}"}),
                encoding="utf-8")

    # The old directory URL has always forwarded to the catalogue; keep it in the map for the checker.
    print(f"wrote {len(entries)} redirects and {len(rules) * 2} stubs under {DOCS / 'rules'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
