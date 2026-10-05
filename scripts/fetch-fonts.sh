#!/usr/bin/env bash
# Fetch the two OFL faces Ravam bundles. Run once after cloning.
#
# Deliberately a script rather than committed binaries: fonts are large, they are
# upstream's to version, and the licence travels with the download.
set -euo pipefail

DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/res/font"
mkdir -p "$DEST"

SG="https://raw.githubusercontent.com/floriankarsten/space-grotesk/master/fonts/ttf"
FC="https://raw.githubusercontent.com/tonsky/FiraCode/master/distr/ttf"

get() { echo "  $2"; curl -fsSL "$1" -o "$DEST/$2"; }

echo "Fetching fonts into $DEST"
get "$SG/SpaceGrotesk-Regular.ttf"  space_grotesk_regular.ttf
get "$SG/SpaceGrotesk-Medium.ttf"   space_grotesk_medium.ttf
get "$SG/SpaceGrotesk-SemiBold.ttf" space_grotesk_semibold.ttf
get "$SG/SpaceGrotesk-Bold.ttf"     space_grotesk_bold.ttf
get "$FC/FiraCode-Regular.ttf"      fira_code_regular.ttf

echo "Done. Both faces are SIL OFL 1.1."
