#!/usr/bin/env bash
# Refresh the vendored gateway contract snapshot from hermes-agent (#1374).
# Usage: scripts/sync-gateway-contract.sh [ref]   (default: main)
set -euo pipefail

REF="${1:-main}"
REPO="NousResearch/hermes-agent"
CONTRACT_PATH="apps/shared/src/gateway-contract.openrpc.json"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/test/resources/gateway-contract"

SHA="$(gh api "repos/$REPO/commits/$REF" --jq .sha)"
DATE="$(gh api "repos/$REPO/commits/$SHA" --jq .commit.committer.date)"

mkdir -p "$DEST"
gh api -H "Accept: application/vnd.github.raw" "repos/$REPO/contents/$CONTRACT_PATH?ref=$SHA" > "$DEST/openrpc.json"
jq -e '.methods | length > 0' "$DEST/openrpc.json" > /dev/null

printf 'repo: %s\nref: %s\nsha: %s\ndate: %s\npath: %s\n' "$REPO" "$REF" "$SHA" "${DATE%%T*}" "$CONTRACT_PATH" > "$DEST/SOURCE"
echo "Vendored $REPO@$SHA. Run ./gradlew testDebugUnitTest --tests '*GatewayContract*' and review the diff."
