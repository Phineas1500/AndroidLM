#!/usr/bin/env bash
# Cross-compile the BigMoeOnEdge engine (bmoe-cli + llama.cpp libs) for Android arm64 and stage
# it into an app's jniLibs. Bash port of BigMoeOnEdge's scripts/build-android.ps1.
#
# Usage: build-android-engine.sh <BigMoeOnEdge checkout> <jniLibs/arm64-v8a dir> [build dir name]
# Env:   ANDROID_HOME (SDK root), ARM_ARCH (default armv8.2-a+dotprod+fp16, upstream's choice;
#        use armv8.2-a+dotprod+fp16+i8mm for Pixel 8-class cores: faster prefill, but the binary
#        will crash with SIGILL on older cores such as the Snapdragon 865).
set -euo pipefail

ROOT=$(cd "$1" && pwd)
JNI=$2
BUILD=${3:-build-android}
ARM_ARCH=${ARM_ARCH:-armv8.2-a+dotprod+fp16}
API=29

# Our patches to the engine (patches/*.patch) and to its llama.cpp submodule
# (patches/llama.cpp/*.patch, applied inside third_party/llama.cpp), in name order. Applying is
# idempotent: a series already in the tree is recognised by reverse-applying it, last patch
# first, to a scratch copy of the files it touches (patches that edit the same file can only be
# checked in sequence, not one at a time).
series_applied() {  # <repo dir> <patch>...
  local dir=$1 tmp f i ok=0; shift
  local -a ps=("$@")
  tmp=$(mktemp -d)
  for f in $(sed -n 's|^diff --git a/\([^ ]*\) b/.*|\1|p' "${ps[@]}" | sort -u); do
    if [ -f "$dir/$f" ]; then mkdir -p "$tmp/$(dirname "$f")"; cp "$dir/$f" "$tmp/$f"; fi
  done
  for ((i = ${#ps[@]} - 1; i >= 0; i--)); do
    (cd "$tmp" && git apply --reverse "${ps[i]}" 2>/dev/null) || { ok=1; break; }
  done
  rm -r "$tmp"
  return $ok
}
apply_series() {  # <repo dir> <patch>...
  local dir=$1 p; shift
  [ -f "${1:-}" ] || return 0
  if series_applied "$dir" "$@"; then
    echo "already applied: $(basename -a "$@" | tr '\n' ' ')"
    return 0
  fi
  for p in "$@"; do
    if git -C "$dir" apply "$p"; then echo "applied $(basename "$p")"; else echo "patch does not apply: $p" >&2; exit 1; fi
  done
}
PATCHES=$(cd "$(dirname "$0")/../patches" && pwd)
apply_series "$ROOT" "$PATCHES"/*.patch
apply_series "$ROOT/third_party/llama.cpp" "$PATCHES"/llama.cpp/*.patch

NDK=$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)
CMAKE_DIR=$(ls -d "$ANDROID_HOME"/cmake/* | sort -V | tail -1)/bin
HOST_TAG=$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)
echo "NDK: $NDK ($HOST_TAG) | ARM_ARCH: $ARM_ARCH"

"$CMAKE_DIR/cmake" -S "$ROOT" -B "$ROOT/$BUILD" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$CMAKE_DIR/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API \
  -DCMAKE_BUILD_TYPE=Release \
  -DBMOE_BUILD_TESTS=OFF -DGGML_NATIVE=OFF -DGGML_OPENCL=OFF -DGGML_OPENMP=OFF \
  -DGGML_CPU_ARM_ARCH="$ARM_ARCH" -DLLAMA_CURL=OFF
"$CMAKE_DIR/cmake" --build "$ROOT/$BUILD" -j "${JOBS:-3}"

mkdir -p "$JNI"
rm -f "$JNI"/*.so
# Android only extracts and marks executable files named lib*.so, hence the CLI's name
cp "$ROOT/$BUILD/cli/bmoe-cli" "$JNI/libbmoe-cli.so"
for name in libggml.so libggml-base.so libggml-cpu.so libllama.so libllama-common.so; do
  src=$(find "$ROOT/$BUILD" -name "$name" | head -1)
  [ -n "$src" ] || { echo "$name not found"; exit 1; }
  cp "$src" "$JNI/$name"
done
cp "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$JNI/"
ls -la "$JNI"
