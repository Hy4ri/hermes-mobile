#!/bin/sh
# Export the net Android customization, not superseded intermediate backend experiments.
set -eu
if [ "$#" -ne 3 ]; then
    echo "Usage: $0 <upstream-base-commit> <feature-head-commit> <empty-output-directory>" >&2
    exit 2
fi
# PR #1819: record immutable revisions even when the caller passes HEAD.
base=$(git rev-parse --verify "${1}^{commit}")
head=$(git rev-parse --verify "${2}^{commit}")
out=$3
mkdir -p "$out"
git diff --binary "$base" "$head" > "$out/hermes-mobile-realtime21.patch"
printf '%s\n' "Mobile base: $base" "Mobile head: $head" \
    "Talk protocol source: 3b8f88bc9b39e7916523964debdce504af4d4990" \
    "No Hermes Talk server program patch is required." > "$out/BASES.txt"
printf '%s\n' "Exported Android-only patch to $out"
