#!/bin/sh
# Export this feature branch before rebasing onto a new Hermes Mobile release.
set -eu
if [ "$#" -ne 3 ]; then
    echo "Usage: $0 <upstream-base-commit> <feature-head-commit> <output-directory>" >&2
    exit 2
fi
base=$1
head=$2
out=$3
mkdir -p "$out"
git format-patch --stdout "$base..$head" > "$out/hermes-mobile-realtime21.patch"
cp patches/hermes-talk/0001-mobile-subscription-guard.patch "$out/hermes-talk-mobile.patch"
printf '%s\n' "Mobile base: $base" "Mobile head: $head" \
    "Talk base: 3b8f88bc9b39e7916523964debdce504af4d4990" > "$out/BASES.txt"
printf '%s\n' "Exported patch series to $out"
