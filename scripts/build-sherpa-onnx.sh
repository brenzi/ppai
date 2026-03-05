#!/bin/bash
# Build script for cross-compiling sherpa-onnx TTS for Android.
#
# Prerequisites:
#   - CMake 3.22+ installed
#   - Android NDK installed (via Android Studio SDK Manager or standalone)
#   - ANDROID_NDK_HOME or ANDROID_NDK environment variable set
#
# Usage:
#   ./scripts/build-sherpa-onnx.sh
#
# This uses sherpa-onnx's own Android build approach: download pre-compiled
# onnxruntime for Android, then build sherpa-onnx from source linking against
# it. Outputs .so files into jniLibs/ and espeak-ng-data into assets/.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
SHERPA_SRC="$PROJECT_DIR/sherpa-onnx"
JNI_LIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs"
ASSETS_DIR="$PROJECT_DIR/app/src/main/assets"
BUILD_BASE="$PROJECT_DIR/build-sherpa-onnx"

if [ ! -f "$SHERPA_SRC/CMakeLists.txt" ]; then
    echo "ERROR: sherpa-onnx source not found at $SHERPA_SRC"
    echo "Run: git submodule update --init sherpa-onnx"
    exit 1
fi

# Resolve Android NDK — check ANDROID_NDK_HOME, ANDROID_NDK, then common paths
ANDROID_NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
if [ -z "$ANDROID_NDK" ]; then
    for search in \
        "$HOME/Android/Sdk/ndk" \
        "$HOME/Library/Android/sdk/ndk" \
        "${ANDROID_HOME:-__none__}/ndk" \
        "${ANDROID_SDK_ROOT:-__none__}/ndk"; do
        if [ -d "$search" ]; then
            ANDROID_NDK=$(ls -d "$search/"* 2>/dev/null | sort -V | tail -1)
            [ -n "$ANDROID_NDK" ] && break
        fi
    done
fi

if [ -z "$ANDROID_NDK" ] || [ ! -d "$ANDROID_NDK" ]; then
    echo "ERROR: Android NDK not found. Set ANDROID_NDK_HOME or ANDROID_NDK."
    exit 1
fi

TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN_FILE" ]; then
    echo "ERROR: Android toolchain not found at $TOOLCHAIN_FILE"
    exit 1
fi

echo "Using Android NDK: $ANDROID_NDK"
echo "sherpa-onnx source: $SHERPA_SRC"

ONNXRUNTIME_VERSION=1.17.1
MIN_API=26
NCPU=$(nproc 2>/dev/null || sysctl -n hw.logicalcpu 2>/dev/null || echo 4)

# Download pre-compiled onnxruntime for Android (shared ZIP with all ABIs)
ORT_DIR="$BUILD_BASE/onnxruntime-$ONNXRUNTIME_VERSION"
if [ ! -d "$ORT_DIR/jni" ]; then
    echo "Downloading onnxruntime $ONNXRUNTIME_VERSION for Android..."
    mkdir -p "$ORT_DIR"
    ORT_ZIP="$ORT_DIR/onnxruntime-android-${ONNXRUNTIME_VERSION}.zip"
    curl -L -o "$ORT_ZIP" \
        "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v${ONNXRUNTIME_VERSION}/onnxruntime-android-${ONNXRUNTIME_VERSION}.zip"
    unzip -o -q "$ORT_ZIP" -d "$ORT_DIR"
    rm -f "$ORT_ZIP"
fi

ORT_INCLUDE="$ORT_DIR/headers"

for abi in arm64-v8a x86_64; do
    echo ""
    echo "========================================="
    echo "Building sherpa-onnx for $abi"
    echo "========================================="

    BUILD_DIR="$BUILD_BASE/$abi"
    OUTPUT_DIR="$JNI_LIBS_DIR/$abi"
    mkdir -p "$BUILD_DIR" "$OUTPUT_DIR"

    ORT_LIB="$ORT_DIR/jni/$abi"
    if [ ! -f "$ORT_LIB/libonnxruntime.so" ]; then
        echo "ERROR: onnxruntime not found for $abi at $ORT_LIB"
        exit 1
    fi

    export SHERPA_ONNXRUNTIME_LIB_DIR="$ORT_LIB"
    export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ORT_INCLUDE"

    # Wipe stale CMake cache if source dir changed (e.g. different checkout path)
    if [ -f "$BUILD_DIR/CMakeCache.txt" ]; then
        cached_src=$(grep -m1 'CMAKE_HOME_DIRECTORY:INTERNAL=' "$BUILD_DIR/CMakeCache.txt" | cut -d= -f2)
        if [ -n "$cached_src" ] && [ "$cached_src" != "$SHERPA_SRC" ]; then
            echo "CMake cache points to $cached_src, wiping stale build dir"
            rm -rf "$BUILD_DIR"
            mkdir -p "$BUILD_DIR"
        fi
    fi

    cmake -S "$SHERPA_SRC" -B "$BUILD_DIR" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$MIN_API" \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=ON \
        -DSHERPA_ONNX_ENABLE_TTS=ON \
        -DSHERPA_ONNX_ENABLE_JNI=ON \
        -DSHERPA_ONNX_ENABLE_BINARY=OFF \
        -DSHERPA_ONNX_ENABLE_C_API=OFF \
        -DSHERPA_ONNX_ENABLE_PYTHON=OFF \
        -DSHERPA_ONNX_ENABLE_TESTS=OFF \
        -DSHERPA_ONNX_ENABLE_CHECK=OFF \
        -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
        -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF \
        -DSHERPA_ONNX_ENABLE_GPU=OFF \
        -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
        -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF \
        -DBUILD_PIPER_PHONMIZE_EXE=OFF \
        -DBUILD_PIPER_PHONMIZE_TESTS=OFF \
        -DBUILD_ESPEAK_NG_EXE=OFF \
        -DBUILD_ESPEAK_NG_TESTS=OFF

    cmake --build "$BUILD_DIR" --parallel "$NCPU"

    # Copy JNI shared library
    cp "$BUILD_DIR/lib/libsherpa-onnx-jni.so" "$OUTPUT_DIR/"

    # Copy onnxruntime shared library
    cp "$ORT_LIB/libonnxruntime.so" "$OUTPUT_DIR/"

    echo "Built sherpa-onnx for $abi"
done

# Build espeak-ng-data from source.
# The cross-compile can't run host tools, so we build espeak-ng natively for the
# host first to compile phoneme data (phontab, phondata, phonindex, intonations).
# Uses the espeak-ng source fetched by CMake during the Android build above.
ESPEAK_DEST="$ASSETS_DIR/sherpa-onnx-espeak-ng-data"
if [ ! -f "$ESPEAK_DEST/phontab" ]; then
    FIRST_BUILD="$BUILD_BASE/arm64-v8a"
    ESPEAK_SRC="$FIRST_BUILD/_deps/espeak_ng-src"
    if [ ! -d "$ESPEAK_SRC" ]; then
        echo "ERROR: espeak-ng source not found at $ESPEAK_SRC"
        exit 1
    fi

    echo ""
    echo "========================================="
    echo "Building espeak-ng-data from source (host)"
    echo "========================================="

    HOST_BUILD="$BUILD_BASE/espeak-ng-host"
    mkdir -p "$HOST_BUILD"

    cmake -S "$ESPEAK_SRC" -B "$HOST_BUILD" \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_ESPEAK_NG_EXE=ON \
        -DBUILD_ESPEAK_NG_TESTS=OFF \
        -DBUILD_SHARED_LIBS=OFF

    cmake --build "$HOST_BUILD" --parallel "$NCPU"

    # The compiled data lands in <build>/_deps/espeak_ng-src/espeak-ng-data/
    # or in the build dir itself — find phontab
    ESPEAK_DATA_SRC=$(find "$HOST_BUILD" -name "phontab" -type f | head -1)
    if [ -z "$ESPEAK_DATA_SRC" ]; then
        echo "ERROR: phontab not found after host build of espeak-ng"
        exit 1
    fi
    ESPEAK_DATA_DIR=$(dirname "$ESPEAK_DATA_SRC")

    mkdir -p "$ESPEAK_DEST"
    rm -rf "$ESPEAK_DEST"
    cp -r "$ESPEAK_DATA_DIR" "$ESPEAK_DEST"
    echo "espeak-ng-data built and copied to $ESPEAK_DEST ($(ls "$ESPEAK_DEST" | wc -l) entries)"
else
    echo "espeak-ng-data already present at $ESPEAK_DEST"
fi

echo ""
echo "========================================="
echo "Build complete! sherpa-onnx libraries:"
find "$JNI_LIBS_DIR" -name "libsherpa-onnx*" -o -name "libonnxruntime*" 2>/dev/null | xargs ls -lh 2>/dev/null || true
echo "========================================="
