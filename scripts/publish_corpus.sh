#!/usr/bin/env bash
# Publish the corpus databases to a Hugging Face dataset repository and record their URLs and
# checksums in assets/manifest.json. Run this where the databases are (they are 21GB+), after
# `huggingface-cli login` with a token that can write to the repository.
#
# Usage: publish_corpus.sh <hf-user>/<dataset-name> <dir containing wiki.db and voyage.db>
# This makes the files public. It is not run by any other script.
set -euo pipefail

REPO=$1
DIR=$2
ROOT=$(cd "$(dirname "$0")/.." && pwd)
command -v huggingface-cli >/dev/null || { echo "pip install -U huggingface_hub first" >&2; exit 1; }

huggingface-cli repo create "$REPO" --type dataset -y || true
huggingface-cli upload "$REPO" "$ROOT/assets/hf_dataset_card.md" README.md --repo-type dataset
for f in wiki.db voyage.db; do
  echo "hashing $f..."
  sha=$(sha256sum "$DIR/$f" | cut -d' ' -f1)
  echo "uploading $f ($sha)..."
  huggingface-cli upload "$REPO" "$DIR/$f" "$f" --repo-type dataset
  python3 - "$ROOT/assets/manifest.json" "$f" "$sha" "https://huggingface.co/datasets/$REPO/resolve/main/$f" <<'EOF'
import json, sys
path, name, sha, url = sys.argv[1:5]
m = json.load(open(path))
for entry in m["files"]:
    if entry["name"] == name:
        entry["sha256"], entry["url"] = sha, url
json.dump(m, open(path, "w"), indent=2)
open(path, "a").write("\n")
EOF
done
echo "manifest updated; commit assets/manifest.json"
