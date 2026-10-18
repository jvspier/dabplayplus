#!/bin/sh
# Assembles the website (GitHub Pages) into $1 (default: _site at the repo root) from what's already in the repo:
# the pages here, the README screenshots, the app icon and font, the social preview. Fills in the icon sprite and
# the app's current version. Preview it locally: site/build.sh && xdg-open _site/index.html
set -e
repo=$(cd "$(dirname "$0")/.." && pwd)
out=${1:-$repo/_site}
version=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$repo/android/app/build.gradle.kts")
[ -n "$version" ] || { echo "no versionName found" >&2; exit 1; }

rm -rf "$out"; mkdir -p "$out/img" "$out/fonts"
cp "$repo/site/style.css" "$out/"
cp "$repo/docs/social-preview.png" "$out/"
cp "$repo/android/app/src/main/ic_launcher-playstore.png" "$out/img/icon.png"
for s in home now-playing now-playing-day stations-day panel-settings-day; do cp "$repo/docs/screenshots/$s.png" "$out/img/"; done
for w in regular medium semibold; do cp "$repo/android/app/src/main/res/font/inter_$w.ttf" "$out/fonts/"; done
for page in index privacy; do
  python3 - "$repo/site/$page.html" "$repo/site/icons.svg" "$version" > "$out/$page.html" <<'EOF'
import sys
page, icons, version = sys.argv[1:4]
html = open(page, encoding="utf-8").read()
print(html.replace("{{ICONS}}", open(icons, encoding="utf-8").read().strip()).replace("{{VERSION}}", version), end="")
EOF
done
touch "$out/.nojekyll"                                   # plain files: no Jekyll processing on GitHub Pages
echo "site $version written to $out"
