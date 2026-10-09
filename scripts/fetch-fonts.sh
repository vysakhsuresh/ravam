#!/usr/bin/env bash
# Fetch the two OFL faces Ravam bundles. Run once after cloning.
#
# Deliberately a script rather than committed binaries: fonts are large, they are
# upstream's to version, and the licence travels with the download.
#
# Faces are resolved through the Google Fonts CSS API rather than hardcoded repo
# paths. Upstream moved its files twice already — and, more importantly, the
# space-grotesk repo ships no static SemiBold at all any more, only a variable
# font. The CSS API instances the variable font per weight, so every face the
# theme asks for exists. Asking as an old browser is what makes it serve
# TrueType instead of woff2; Android resources accept .ttf/.ttc/.otf only.
set -euo pipefail

DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/res/font"
mkdir -p "$DEST"

UA="Mozilla/4.0"
API="https://fonts.googleapis.com/css2"

# Pull the .ttf URL for one family+weight out of the generated @font-face block.
url_for() { # family-with-plusses, weight
  curl -fsSL -H "User-Agent: $UA" "$API?family=$1:wght@$2" \
    | grep -oE 'https://[^)]+\.ttf'
}

get() { # family-with-plusses, weight, destination filename
  local url
  url="$(url_for "$1" "$2")"
  [ -n "$url" ] || { echo "No TTF for $1 $2" >&2; return 1; }
  echo "  $3"
  curl -fsSL "$url" -o "$DEST/$3"
}

echo "Fetching fonts into $DEST"
get Space+Grotesk 400 space_grotesk_regular.ttf
get Space+Grotesk 500 space_grotesk_medium.ttf
get Space+Grotesk 600 space_grotesk_semibold.ttf
get Space+Grotesk 700 space_grotesk_bold.ttf
get Fira+Code     400 fira_code_regular.ttf

echo "Done. Both faces are SIL OFL 1.1."
