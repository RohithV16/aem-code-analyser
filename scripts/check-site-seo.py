#!/usr/bin/env python3
"""Fails when the published site's discovery surfaces drift from the generated rule reference.

The rule pages are generated (RuleDocsGenerator writes one per rule id), and the sitemap is produced
by jekyll-sitemap from the source tree at build time, so nothing here can be checked by grepping a
committed sitemap. This script instead derives the URL set and the structured data from the same
sources Jekyll reads and asserts the invariants that make the site indexable:

* every rule in the registry has its own page under /issues/<anchor>/,
* those pages are indexable (front matter, no ``sitemap: false``) and have distinct titles,
* every JSON-LD block in the layout include is valid JSON once its Liquid values are substituted,
* the layout emits the SoftwareApplication, TechArticle and BreadcrumbList entities.

Run with no arguments from the repository root; exits non-zero on the first broken invariant.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

DOCS = Path(sys.argv[1] if len(sys.argv) > 1 else "docs")
JSON_LD = re.compile(r'<script type="application/ld\+json">(.*?)</script>', re.S)
LIQUID_OUTPUT = re.compile(r"\{\{.*?\}\}", re.S)


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


def site_config() -> dict[str, str]:
    config: dict[str, str] = {}
    text = (DOCS / "_config.yml").read_text(encoding="utf-8")
    for line in text.split("\n"):
        match = re.match(r"^(url|baseurl|title|repository):\s*(.+)$", line)
        if match:
            config[match.group(1)] = match.group(2).strip().strip('"')
    return config


def published_pages() -> list[dict[str, str]]:
    pages = []
    for path in sorted(DOCS.rglob("*")):
        if not path.is_file() or path.suffix not in (".html", ".md"):
            continue
        if any(part.startswith("_") for part in path.relative_to(DOCS).parts[:-1]):
            continue
        if path.name == "README.md":
            continue
        data = front_matter(path)
        pages.append({"path": path, "url": page_url(path), **data})
    return pages


def check_structure(pages: list[dict[str, str]]) -> list[dict[str, str]]:
    rule_pages = [p for p in pages if re.fullmatch(r"/issues/[a-z0-9-]+/", p["url"])]
    index = [p for p in pages if p["url"] == "/issues/"]
    assert index, "the filterable index page /issues/ is missing"
    # Derived, not hard-coded: the rule set grows, and a literal count turns every new rule into a
    # site failure. The invariant that matters is one page per rule card on the index.
    cards = len(re.findall(r'class="rule-card"', Path(str(index[0]["path"])).read_text(encoding="utf-8")))
    assert cards > 0, "the /issues/ index has no rule cards"
    assert len(rule_pages) == cards, (
        f"expected one page per rule card ({cards}), found {len(rule_pages)}")

    titles: dict[str, str] = {}
    for page in rule_pages:
        anchor = page["url"].strip("/").split("/")[-1]
        assert page.get("layout") == "default", f"{page['url']} must use the default layout (canonical + JSON-LD)"
        assert page.get("rule_id"), f"{page['url']} is missing rule_id front matter"
        assert page["rule_id"].lower() == anchor, f"{page['url']} rule_id does not match its URL"
        assert page.get("description"), f"{page['url']} has no meta description"
        assert "sitemap" not in page or page["sitemap"] != "false", f"{page['url']} is excluded from the sitemap"
        title = page.get("title", "")
        assert title.startswith(page["rule_id"] + " "), f"{page['url']} title must lead with its rule id: {title}"
        assert title not in titles, f"{page['url']} and {titles[title]} share the title {title!r}"
        titles[title] = page["url"]

    index_page = index[0]
    assert index_page.get("sitemap") != "false", "/issues/ must stay indexable"
    return rule_pages


def check_structured_data() -> None:
    layout = (DOCS / "_layouts/default.html").read_text(encoding="utf-8")
    include = DOCS / "_includes/structured-data.html"
    assert include.is_file(), "docs/_includes/structured-data.html is missing"
    assert 'rel="canonical"' in layout, "the layout must emit a canonical link"
    assert "structured-data.html" in layout, "the layout must include the structured data partial"

    text = include.read_text(encoding="utf-8")
    blocks = JSON_LD.findall(text)
    assert len(blocks) == 3, f"expected SoftwareApplication, TechArticle and BreadcrumbList, found {len(blocks)}"
    # Values are interpolated with jsonify so a quote in a rule description cannot break the block;
    # the two exceptions are the pre-escaped crumb array and its numeric position counter.
    types = set()
    for block in blocks:
        for expression in LIQUID_OUTPUT.findall(block):
            if expression.strip() in ("{{ sd_crumbs }}", "{{ sd_position }}"):
                continue
            assert "jsonify" in expression, f"JSON-LD value is not escaped with jsonify: {expression.strip()}"
        parsed = json.loads(LIQUID_OUTPUT.sub('"x"', block))
        assert parsed["@context"] == "https://schema.org", "every block must declare the schema.org context"
        types.add(parsed["@type"])
    assert types == {"SoftwareApplication", "TechArticle", "BreadcrumbList"}, f"unexpected entity set: {types}"


def check_fonts() -> None:
    """No page may load a stylesheet or face from another origin: the site has to paint offline."""
    offenders = []
    for path in sorted(DOCS.rglob("*")):
        if not path.is_file() or path.suffix not in (".html", ".md", ".css", ".js"):
            continue
        if any(part.startswith("_") for part in path.relative_to(DOCS).parts[:-1]):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for origin in ("fonts.googleapis.com", "fonts.gstatic.com"):
            if origin in text:
                offenders.append(f"{path.relative_to(DOCS)} -> {origin}")
    assert not offenders, "third-party font origins remain: " + ", ".join(offenders)

    css = (DOCS / "assets/css/site.css").read_text(encoding="utf-8")
    layout = (DOCS / "_layouts/default.html").read_text(encoding="utf-8")
    for family, weight, file in (
        ("Geist", "100 900", "geist-latin.woff2"),
        ("JetBrains Mono", "400 500", "jetbrains-mono-latin.woff2"),
    ):
        face = re.search(r'@font-face\s*\{[^}]*font-family:\s*"' + re.escape(family) + r'"[^}]*\}', css, re.S)
        assert face, f"site.css has no @font-face for {family}"
        assert "font-display: swap" in face.group(0), f"{family} must use font-display: swap"
        assert f"font-weight: {weight}" in face.group(0), f"{family} must declare the variable weight range {weight}"
        assert f'url("../fonts/{file}")' in face.group(0), f"{family} must load /assets/fonts/{file}"
        assert "unicode-range:" in face.group(0), f"{family} must declare its latin unicode-range"
        assert f"/assets/fonts/{file}" in layout, f"the layout must preload {file}"
        font_file = DOCS / "assets/fonts" / file
        assert font_file.is_file(), f"docs/assets/fonts/{file} is missing"
        assert font_file.read_bytes()[:4] == b"wOF2", f"docs/assets/fonts/{file} is not a woff2 file"
    assert layout.count('rel="preload"') >= 2, "both faces must be preloaded"


def check_search_index(pages: list[dict[str, str]]) -> None:
    """The ⌘K palette is only as complete as its corpus, and the corpus is a generated file."""
    index_path = DOCS / "assets/search-index.json"
    assert index_path.is_file(), "docs/assets/search-index.json is missing (run scripts/build-search-index.py)"
    try:
        entries = json.loads(index_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as failure:
        raise AssertionError(f"search-index.json is not valid JSON: {failure}") from failure
    assert isinstance(entries, list) and entries, "search-index.json must be a non-empty array"

    ids = [entry.get("id") for entry in entries]
    assert len(ids) == len(set(ids)), "search-index.json has duplicate ids"
    for entry in entries:
        for key in ("id", "title", "url", "kind", "text"):
            assert entry.get(key), f"search index entry is missing {key}: {entry}"
        assert entry["url"].startswith("/"), f"search index URL must be site-relative: {entry['url']}"

    urls = {entry["url"] for entry in entries}
    # Every published page is searchable, including the per-rule pages.
    missing = sorted({page["url"] for page in pages} - urls)
    assert not missing, f"search-index.json omits published pages: {', '.join(missing[:5])}"

    # Every rule card is a hit that lands on its anchor on /issues/, not just on the index.
    rule_pages = [page for page in pages if re.fullmatch(r"/issues/[a-z0-9-]+/", page["url"])]
    for page in rule_pages:
        anchor = page["url"].strip("/").split("/")[-1]
        assert f"/issues/#{anchor}" in urls, f"search-index.json has no hit for /issues/#{anchor}"
        hit = next(entry for entry in entries if entry["url"] == f"/issues/#{anchor}")
        assert hit["id"] == anchor.upper(), f"rule hit id must be the rule id: {hit}"

    # Every page section a reader can link to must be a hit too, so "suppression" lands on the anchor.
    for page in pages:
        body = Path(str(page["path"])).read_text(encoding="utf-8", errors="ignore")
        for anchor in re.findall(r'<section[^>]*\bid="([a-z0-9-]+)"', body):
            assert f"{page['url']}#{anchor}" in urls, f"search-index.json has no hit for {page['url']}#{anchor}"

    header = (DOCS / "_includes/site-header.html").read_text(encoding="utf-8")
    assert 'id="searchDialog"' in header, "the header must render the search palette"
    assert 'id="searchOpen"' in header, "the header must render the search trigger"
    assert "aria-keyshortcuts=\"Meta+K Control+K\"" in header, "the trigger must advertise the shortcut"
    script = (DOCS / "assets/js/site.js").read_text(encoding="utf-8")
    assert "assets/search-index.json" in script, "site.js must load the generated corpus"
    assert "'/issues/'" in script and "encodeURIComponent" in script, "the palette needs the issues filter fallback"


def main() -> int:
    config = site_config()
    pages = published_pages()
    rule_pages = check_structure(pages)
    check_structured_data()
    check_fonts()
    check_search_index(pages)

    base = config["url"] + config["baseurl"]
    indexable = [p for p in pages if p.get("sitemap") != "false"]
    urls = sorted({base + p["url"] for p in indexable})

    print(f"pages published:            {len(indexable)}")
    print(f"rule pages under /issues/:  {len(rule_pages)}")
    print(f"distinct rule page titles:  {len({p['title'] for p in rule_pages})}")
    print(f"sitemap URLs:               {len(urls)}")
    for url in urls:
        if "/issues/aem-" in url:
            print(f"  {url}")
    print("structured data:            SoftwareApplication + TechArticle + BreadcrumbList, all blocks parse as JSON")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        sys.exit(1)
