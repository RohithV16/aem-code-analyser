#!/usr/bin/env python3
"""Writes docs/assets/search-index.json — the corpus behind the site-wide ⌘K palette.

Jekyll is not a dependency of the build host beyond the static site itself, and a Liquid page cannot
reliably read the rule cards the generator writes, so the corpus is derived here from the same source
tree Jekyll publishes: every page's front matter, every ``<section id>`` heading, and every rule card
on ``/issues/``. Keeping it a plain file makes it inspectable, cacheable and diffable, and
``scripts/check-site-seo.py`` regenerates it in memory to fail on drift.

The output is deterministic: entries are sorted, nothing carries a build time, and re-running on an
unchanged tree produces a byte-identical file.

Usage: scripts/build-search-index.py [docs-root]
"""

from __future__ import annotations

import html
import json
import re
import sys
from pathlib import Path

DOCS = Path(sys.argv[1] if len(sys.argv) > 1 else "docs")
OUT = DOCS / "assets" / "search-index.json"

SECTION = re.compile(r'<section[^>]*\bid="([a-z0-9-]+)"[^>]*>(.*?)</section>', re.S)
HEADING = re.compile(r"<h2[^>]*>(.*?)</h2>", re.S)
CARD = re.compile(r'<article class="rule-card" id="([a-z0-9-]+)"[^>]*data-family="([A-Z0-9]+)"[^>]*data-search="([^"]*)"(.*?)</article>', re.S)
CARD_TITLE = re.compile(r'<span class="rule-card__id">([^<]+)</span>.*?<span class="rule-card__name">([^<]+)</span>', re.S)
TAG = re.compile(r"<[^>]+>")


def front_matter(path: Path) -> dict[str, str]:
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        return {}
    end = text.find("\n---", 3)
    if end < 0:
        return {}
    data: dict[str, str] = {}
    key = None
    for line in text[4:end].split("\n"):
        if not line.strip():
            continue
        if line[:1].isspace() and key:
            data[key] = (data[key] + " " + line.strip()).strip()
            continue
        if ":" in line:
            key, value = line.split(":", 1)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] == '"':
                value = value[1:-1].replace('\\"', '"').replace("\\\\", "\\")
            data[key.strip()] = value
    return data


def page_url(path: Path) -> str:
    rel = path.relative_to(DOCS).as_posix()
    for index in ("index.html", "index.md"):
        if rel.endswith(index):
            rel = rel[: -len(index)]
    return "/" + rel if rel else "/"


def plain(value: str) -> str:
    text = TAG.sub(" ", html.unescape(value))
    return re.sub(r"\s+", " ", text).strip()


def truncate(text: str, limit: int = 420) -> str:
    text = text.strip()
    if len(text) <= limit:
        return text
    cut = text.rfind(" ", 0, limit)
    return text[: cut if cut > limit // 2 else limit].rstrip(" ,;:") + " …"


def kind_for(url: str) -> str:
    if re.fullmatch(r"/issues/[a-z0-9-]+/", url):
        return "rule-page"
    if url.startswith("/issues/"):
        return "page"
    if url == "/reference/" or url == "/getting-started/":
        return "guide"
    if url == "/demo/":
        return "demo"
    if url == "/compare/" or url == "/verify/":
        return "decision"
    return "page"


def published_pages() -> list[Path]:
    pages = []
    for path in sorted(DOCS.rglob("*")):
        if not path.is_file() or path.suffix not in (".html", ".md"):
            continue
        if any(part.startswith("_") for part in path.relative_to(DOCS).parts[:-1]):
            continue
        if path.name == "README.md":
            continue
        # Meta-refresh stubs and the 404 are publishing surfaces, not pages a reader should find.
        if front_matter(path).get("sitemap") == "false":
            continue
        pages.append(path)
    return pages


def page_entries() -> list[dict[str, str]]:
    entries: list[dict[str, str]] = []
    for path in published_pages():
        data = front_matter(path)
        url = page_url(path)
        text = path.read_text(encoding="utf-8")
        title = data.get("title") or ("AEM Code Analyser" if url == "/" else url)
        body = text.split("\n---", 2)[-1]
        summary = data.get("description", "")
        entries.append({
            "id": url,
            "title": title,
            "url": url,
            "kind": kind_for(url),
            "text": truncate(" ".join(part for part in (summary, plain(body)) if part)),
        })

        # Page sections are separate hits so "suppression" lands on /reference/#suppression
        # rather than the top of a 2 000-word page.
        for match in SECTION.finditer(body):
            anchor, inner = match.group(1), match.group(2)
            heading = HEADING.search(inner)
            section_title = plain(heading.group(1)) if heading else anchor.replace("-", " ").title()
            entries.append({
                "id": f"{url}#{anchor}",
                "title": f"{title} — {section_title}" if url != "/" else section_title,
                "url": f"{url}#{anchor}",
                "kind": "section",
                "text": truncate(section_title + " " + plain(inner)),
            })
    return entries


def rule_entries() -> list[dict[str, str]]:
    index = DOCS / "issues" / "index.html"
    if not index.is_file():
        return []
    text = index.read_text(encoding="utf-8")
    entries = []
    for match in CARD.finditer(text):
        anchor, family, search_text, rest = match.groups()
        title = CARD_TITLE.search(rest)
        rule_id, name = (plain(title.group(1)), plain(title.group(2))) if title else (anchor.upper(), "")
        entries.append({
            "id": plain(rule_id),
            "title": f"{plain(rule_id)} {name}".strip(),
            "url": f"/issues/#{anchor}",
            "kind": "rule",
            "text": truncate(html.unescape(search_text) + " " + family.lower(), 600),
        })
    return entries


def main() -> int:
    entries = page_entries() + rule_entries()
    seen: set[str] = set()
    for entry in entries:
        key = entry["id"]
        suffix = 2
        while entry["id"] in seen:
            entry["id"] = f"{key}#{suffix}"
            suffix += 1
        seen.add(entry["id"])
    entries.sort(key=lambda entry: (entry["kind"] != "page", entry["id"].casefold()))

    payload = json.dumps(entries, ensure_ascii=False, indent=1, sort_keys=True) + "\n"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(payload, encoding="utf-8")
    rules = sum(1 for entry in entries if entry["kind"] == "rule")
    pages = sum(1 for entry in entries if entry["kind"] == "page")
    print(f"wrote {OUT} — {len(entries)} entries ({pages} pages, {rules} rules, "
          f"{len(entries) - pages - rules} sections)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
