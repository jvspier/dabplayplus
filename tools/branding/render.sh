#!/bin/sh
# Renders the README banner (docs/banner.png, 2x for sharp text) with headless Chrome. Run from anywhere.
set -e
here=$(cd "$(dirname "$0")" && pwd)
google-chrome --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
  --default-background-color=00000000 --force-device-scale-factor=2 --window-size=1280,400 \
  --screenshot="$here/../../docs/banner.png" "file://$here/banner.html" 2>/dev/null
echo "written: docs/banner.png"
