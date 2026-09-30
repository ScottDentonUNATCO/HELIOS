#!/bin/bash
# Reassembles chinaskar-v1.html from its chunks and verifies SHA-256.
# Run from a fresh clone: ./assets-src/chinaskar-v1/reassemble.sh
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
MANIFEST="$DIR/MANIFEST.json"
EXPECTED=$(python3 -c "import json;print(json.load(open('$MANIFEST'))['sha256'])")
TARGET=$(python3 -c "import json;print(json.load(open('$MANIFEST'))['target'])")
REPO_ROOT="$(cd "$DIR/../.." && pwd)"
OUT="$REPO_ROOT/$TARGET"
mkdir -p "$(dirname "$OUT")"
cat "$DIR"/chunk_* > "$OUT"
ACTUAL=$(sha256sum "$OUT" | cut -d' ' -f1)
if [ "$ACTUAL" = "$EXPECTED" ]; then
  echo "OK: reassembled $OUT"
  echo "SHA-256: $ACTUAL"
else
  echo "FAIL: SHA-256 mismatch"
  echo "  expected: $EXPECTED"
  echo "  actual:   $ACTUAL"
  exit 1
fi
