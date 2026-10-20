#!/usr/bin/env python3
"""Fills a site page's placeholders (build.sh): {{ICONS}}, {{VERSION}}, {{LATEST}}, {{LATEST_DATE}}, {{CHANGELOG}}."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import changelog  # noqa: E402

page, repo, version = sys.argv[1:4]
versions, refs = changelog.parse(open(os.path.join(repo, "CHANGELOG.md"), encoding="utf-8").read())
html = open(page, encoding="utf-8").read()
for key, value in (
    ("{{ICONS}}", lambda: open(os.path.join(repo, "site", "icons.svg"), encoding="utf-8").read().strip()),
    ("{{VERSION}}", lambda: version),
    ("{{LATEST}}", lambda: changelog.block(versions[0], refs, heading="h3")),
    ("{{LATEST_DATE}}", lambda: versions[0]["date"]),
    ("{{CHANGELOG}}", lambda: "\n".join(changelog.block(v, refs) for v in versions)),
):
    if key in html:
        html = html.replace(key, value())
print(html, end="")
