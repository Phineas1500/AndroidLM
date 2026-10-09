#!/usr/bin/env bash
# Publish the corpus databases to a Hugging Face dataset repository and record their URLs and
# checksums in assets/manifest.json. Run this where the databases are (they are 30GB+), after
# `hf auth login` with a token that can write to the repository.
#
# Usage: publish_corpus.sh <hf-user>/<dataset-name> <dir containing wiki.db, wiki_df.db and voyage.db>
#          [<path prefix in the repository, e.g. v2/> [file ...]]
# A prefix keeps a new build of a file apart from the copy that earlier releases download (their
# manifests name the old path); the files default to wiki.db wiki_df.db voyage.db.
# This makes the files public. It is not run by any other script.
set -euo pipefail

REPO=$1
DIR=$2
PREFIX=${3:-}
shift $(($# < 3 ? $# : 3))
FILES=("$@")
[ ${#FILES[@]} -gt 0 ] || FILES=(wiki.db wiki_df.db voyage.db)
ROOT=$(cd "$(dirname "$0")/.." && pwd)
command -v hf >/dev/null || { echo "pip install -U huggingface_hub first (provides the hf CLI)" >&2; exit 1; }

hf repo create "$REPO" --repo-type dataset || true
hf upload "$REPO" "$ROOT/assets/hf_dataset_card.md" README.md --repo-type dataset
for f in "${FILES[@]}"; do
  echo "hashing $f..."
  sha=$(sha256sum "$DIR/$f" | cut -d' ' -f1)
  echo "uploading $f ($sha)..."
  hf upload "$REPO" "$DIR/$f" "$PREFIX$f" --repo-type dataset
  python3 - "$ROOT/assets/manifest.json" "$f" "$sha" "https://huggingface.co/datasets/$REPO/resolve/main/$PREFIX$f" \
    "$(wc -c < "$DIR/$f" | tr -d ' ')" <<'EOF'
import json, sys
path, name, sha, url, size = sys.argv[1:6]
m = json.load(open(path))
for entry in m["files"]:
    if entry["name"] == name:
        entry["sha256"], entry["url"], entry["bytes"] = sha, url, int(size)
json.dump(m, open(path, "w"), indent=2)
open(path, "a").write("\n")
EOF
done
echo "manifest updated; copy the bytes, sha256 and url into SetupFiles.kt and commit both"
