#!/bin/sh
# Renders the README banner (docs/banner.png, 2x for sharp text), GitHub's social preview (docs/social-preview.png,
# 1280 × 640) and Google Play's feature graphic (docs/feature-graphic.png, 1024 × 500) with headless Chrome. Run from anywhere; re-run after new screenshots (the preview shows home.png).
set -e
here=$(cd "$(dirname "$0")" && pwd)
chrome() { google-chrome --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files "$@" 2>/dev/null; }
chrome --default-background-color=00000000 --force-device-scale-factor=2 --window-size=1280,400 \
  --screenshot="$here/../../docs/banner.png" "file://$here/banner.html"
chrome --window-size=1280,640 --screenshot="$here/../../docs/social-preview.png" "file://$here/social.html"
# Google Play's feature graphic: exactly 1024 × 500, and Play rejects transparency
chrome --window-size=1024,500 --screenshot="$here/../../docs/feature-graphic.png" "file://$here/feature.html"
magick "$here/../../docs/feature-graphic.png" -background '#161826' -alpha remove -alpha off "$here/../../docs/feature-graphic.png"
echo "written: docs/banner.png, docs/social-preview.png, docs/feature-graphic.png"
