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

# Our patches to the engine (see patches/*.patch); applying is idempotent.
for patch in "$(dirname "$0")"/../patches/*.patch; do
  [ -f "$patch" ] || continue
  if git -C "$ROOT" apply --check "$patch" 2>/dev/null; then
    git -C "$ROOT" apply "$patch" && echo "applied $(basename "$patch")"
  elif git -C "$ROOT" apply --reverse --check "$patch" 2>/dev/null; then
    echo "already applied: $(basename "$patch")"
  else
    echo "patch does not apply: $patch" >&2; exit 1
  fi
done

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
