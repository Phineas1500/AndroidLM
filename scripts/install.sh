#!/usr/bin/env bash
# Put AndroidLM on a phone from a computer: download the model and corpus (resumable), verify
# them, push them over adb, and install the APK. The phone never needs a network connection.
#
# Usage: scripts/install.sh [--assets-dir DIR] [--apk FILE] [--no-download] [--no-voyage]
#   --assets-dir DIR  where the large files are kept on this computer (default ./assets-cache)
#   --apk FILE        APK to install (default: newest app-android/app/build/outputs/apk/**.apk)
#   --no-download     only use files already present in the assets directory
#   --no-voyage       skip the optional Wikivoyage corpus
# Needs: adb (Android platform-tools), curl, python3, and about 35GB free here and on the phone.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
MANIFEST=$ROOT/assets/manifest.json
ASSETS=$ROOT/assets-cache
APK=""
DOWNLOAD=1
VOYAGE=1
while [ $# -gt 0 ]; do
  case $1 in
    --assets-dir) ASSETS=$2; shift 2 ;;
    --apk) APK=$2; shift 2 ;;
    --no-download) DOWNLOAD=0; shift ;;
    --no-voyage) VOYAGE=0; shift ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

die() { echo "error: $*" >&2; exit 1; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
filesize() { wc -c < "$1" | tr -d ' '; }

command -v adb >/dev/null || die "adb not found; install Android platform-tools"
[ "$(adb devices | grep -c 'device$')" = 1 ] || die "need exactly one authorised device in 'adb devices'"
mkdir -p "$ASSETS"

DEVICE_ROOT=$(python3 -c "import json;print(json.load(open('$MANIFEST'))['device_root'])")
# name|role|bytes|sha256|url|device_path, one line per file
FILES=$(python3 - "$MANIFEST" <<'EOF'
import json, sys
for f in json.load(open(sys.argv[1]))["files"]:
    print("|".join(str(f.get(k) or "") for k in ("name", "role", "bytes", "sha256", "url", "device_path")))
EOF
)

need=0
while IFS='|' read -r -u 3 name role bytes sha url dpath; do
  [ "$role" = corpus-optional ] && [ $VOYAGE = 0 ] && continue
  need=$((need + bytes))
done 3<<< "$FILES"
free_kb=$(adb shell df /data | awk 'NR==2{print $4}')
echo "assets: $((need / 1000000000)) GB; free on the phone's /data: $((free_kb / 1000000)) GB"
[ $((free_kb * 1024)) -gt $((need + 2000000000)) ] || die "not enough free space on the phone"

# fd 3, because adb and curl inside the loop read stdin and would eat the remaining lines
while IFS='|' read -r -u 3 name role bytes sha url dpath; do
  [ "$role" = corpus-optional ] && [ $VOYAGE = 0 ] && continue
  local_file=$ASSETS/$name
  if [ ! -f "$local_file" ] || [ "$(filesize "$local_file")" != "$bytes" ]; then
    [ $DOWNLOAD = 1 ] && [ -n "$url" ] || die "$name is missing or incomplete in $ASSETS and has no download URL yet"
    echo "downloading $name ($((bytes / 1000000)) MB), resumable..."
    curl -L --fail -C - -o "$local_file" "$url"
  fi
  [ "$(filesize "$local_file")" = "$bytes" ] || die "$name has the wrong size"
  if [ -n "$sha" ]; then
    echo "verifying $name..."
    [ "$(sha256 "$local_file")" = "$sha" ] || die "$name failed its SHA-256 check; delete it and run again"
  else
    echo "warning: no published SHA-256 for $name yet; size checked only"
  fi
  remote=$DEVICE_ROOT/$dpath
  remote_size=$(adb shell "stat -c %s '$remote' 2>/dev/null" | tr -d '\r' || true)
  if [ "$remote_size" = "$bytes" ]; then
    echo "$name is already on the phone"
  else
    adb shell "mkdir -p '$(dirname "$remote")'"
    echo "pushing $name to $remote ..."
    adb push "$local_file" "$remote"
  fi
done 3<<< "$FILES"
# the app reads these without any storage permission, so they must be world-readable
adb shell "chmod 755 '$DEVICE_ROOT' '$DEVICE_ROOT/corpus'; chmod 644 '$DEVICE_ROOT'/*.gguf '$DEVICE_ROOT'/corpus/*.db"

if [ -z "$APK" ]; then
  APK=$(ls -t "$ROOT"/app-android/app/build/outputs/apk/*/*/*.apk 2>/dev/null | head -1 || true)
fi
[ -n "$APK" ] && [ -f "$APK" ] || die "no APK found; build one (see app-android/README.md) or pass --apk"
echo "installing $(basename "$APK") ..."
adb install -r "$APK"

cat <<EOF

Done. On the phone: turn on airplane mode, open AndroidLM (it finds the model and the corpus
by itself), keep Research switched on, type a question and tap Research.
EOF
