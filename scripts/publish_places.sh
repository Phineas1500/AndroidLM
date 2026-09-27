#!/usr/bin/env bash
# Publish places.db to its own Hugging Face dataset repository (it is ODbL, the corpus is
# CC BY-SA) and record its URL and checksum in assets/manifest.json. Run it where places.db is,
# after `hf auth login` with a token that can write to the repository.
#
# Usage: publish_places.sh <hf-user>/<dataset-name> <path to places.db>
# This makes the file public. It is not run by any other script.
set -euo pipefail

REPO=$1
FILE=$2
ROOT=$(cd "$(dirname "$0")/.." && pwd)
command -v hf >/dev/null || { echo "pip install -U huggingface_hub first (provides the hf CLI)" >&2; exit 1; }

hf repo create "$REPO" --repo-type dataset || true
hf upload "$REPO" "$ROOT/assets/hf_places_card.md" README.md --repo-type dataset
echo "hashing places.db..."
sha=$(sha256sum "$FILE" | cut -d' ' -f1)
echo "uploading places.db ($sha)..."
hf upload "$REPO" "$FILE" places.db --repo-type dataset
python3 - "$ROOT/assets/manifest.json" "$sha" "https://huggingface.co/datasets/$REPO/resolve/main/places.db" "$(wc -c < "$FILE" | tr -d ' ')" <<'PY'
import json, sys
path, sha, url, size = sys.argv[1:5]
m = json.load(open(path))
for entry in m["files"]:
    if entry["name"] == "places.db":
        entry["sha256"], entry["url"], entry["bytes"] = sha, url, int(size)
json.dump(m, open(path, "w"), indent=2)
open(path, "a").write("\n")
PY
echo "manifest updated; commit assets/manifest.json"
