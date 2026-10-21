#!/bin/sh
# Renders the made-up station logos (demo-logos.html) for the debug-only DemoActivity: one 512 × 512 PNG per station in
# android/app/src/debug/assets/demo-logos/, named after the station (lowercase, dashes).
set -e
here=$(cd "$(dirname "$0")" && pwd)
out="$here/../../android/app/src/debug/assets/demo-logos"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
google-chrome --headless=new --disable-gpu --hide-scrollbars --default-background-color=00000000 --window-size=2048,1536 \
  --screenshot="$tmp/sheet.png" "file://$here/demo-logos.html" 2>/dev/null
mkdir -p "$out"
i=0
for name in lumen-radio night-drive velvet-jazz harbour-hits echo-rock atlas-news sunset-classics metro-talk ember-beats polar-pop coastline-80s dune-dance; do
  magick "$tmp/sheet.png" -crop 512x512+$(( i % 4 * 512 ))+$(( i / 4 * 512 )) +repage "$out/$name.png"
  i=$((i + 1))
done
echo "written: $out ($i logos)"
