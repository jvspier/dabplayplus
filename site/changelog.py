#!/usr/bin/env python3
"""
Turns ../CHANGELOG.md into HTML for the website, so the site and the repo always say the same: fill.py puts the newest
version's block on the home page ("What's new") and every version on changelog.html; `latest-version` gives just its
number (build.sh checks it against the app's versionName, so a release can't go out without its changelog entry).

Only the subset CHANGELOG.md uses: "## [version] - date", "### New / Fixed / Changed", "- " bullets with indented
continuation lines, plain paragraphs, **bold**, `code`, [text](url) and the "[version]: url" references at the end.

    python3 site/changelog.py CHANGELOG.md latest|all|latest-version
"""
import html
import re
import sys

BADGE = {"New": "green", "Fixed": "orange", "Changed": "blue"}


def inline(text):
    t = html.escape(text, quote=False)
    t = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"`(.+?)`", r"<code>\1</code>", t)
    return re.sub(r"\[(.+?)\]\((https?://[^)\s]+)\)", lambda m: f'<a href="{html.escape(m[2])}">{m[1]}</a>', t)


def parse(md):
    """[{version, date, intro: [str], groups: [(name, [str])]}] in file order (newest first), and the reference links."""
    versions, refs, item, prev_blank = [], {}, None, True
    for line in md.splitlines():
        if m := re.match(r"^\[([^\]]+)\]:\s*(\S+)\s*$", line):
            refs[m[1]] = m[2]
        elif m := re.match(r"^## \[([^\]]+)\] - (\S+)", line):
            versions.append({"version": m[1], "date": m[2], "intro": [], "groups": []})
            item = None
        elif not versions:
            continue                                      # the file's own introduction
        elif m := re.match(r"^### (.+)", line):
            versions[-1]["groups"].append((m[1].strip(), []))
            item = None
        elif line.startswith("- "):
            v = versions[-1]
            if not v["groups"]:
                v["groups"].append(("", []))
            v["groups"][-1][1].append(line[2:].strip())
            item = v["groups"][-1][1]
        elif line.startswith("  ") and line.strip() and item is not None:
            item[-1] += " " + line.strip()
        elif line.strip():
            intro = versions[-1]["intro"]
            if intro and not prev_blank:
                intro[-1] += " " + line.strip()
            else:
                intro.append(line.strip())
            item = None
        prev_blank = not line.strip()
    return versions, refs


def block(v, refs, heading="h2"):
    link = refs.get(v["version"])
    title = f'<a href="{html.escape(link)}">{html.escape(v["version"])}</a>' if link else html.escape(v["version"])
    out = [f'<article class="release" id="v{html.escape(v["version"])}">',
           f'  <{heading}>{title} <time datetime="{v["date"]}">{v["date"]}</time></{heading}>']
    out += [f"  <p>{inline(p)}</p>" for p in v["intro"]]
    for name, items in v["groups"]:
        if name:
            out.append(f'  <div class="badge {BADGE.get(name, "grey")}">{html.escape(name)}</div>')
        out.append("  <ul>" + "".join(f"<li>{inline(i)}</li>" for i in items) + "</ul>")
    out.append("</article>")
    return "\n".join(out)


def main():
    path, what = sys.argv[1], sys.argv[2]
    versions, refs = parse(open(path, encoding="utf-8").read())
    if not versions:
        sys.exit("no versions in " + path)
    if what == "latest":
        print(block(versions[0], refs, heading="h3"))
    elif what == "latest-version":
        print(versions[0]["version"], end="")
    else:
        print("\n".join(block(v, refs) for v in versions))


if __name__ == "__main__":
    main()
