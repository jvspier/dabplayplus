#!/bin/sh
# Renders the README banner (docs/banner.png, 2x for sharp text) and GitHub's social preview (docs/social-preview.png,
# 1280 × 640) with headless Chrome. Run from anywhere; re-run after new screenshots (the preview shows home.png).
set -e
here=$(cd "$(dirname "$0")" && pwd)
chrome() { google-chrome --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files "$@" 2>/dev/null; }
chrome --default-background-color=00000000 --force-device-scale-factor=2 --window-size=1280,400 \
  --screenshot="$here/../../docs/banner.png" "file://$here/banner.html"
chrome --window-size=1280,640 --screenshot="$here/../../docs/social-preview.png" "file://$here/social.html"
echo "written: docs/banner.png, docs/social-preview.png"
