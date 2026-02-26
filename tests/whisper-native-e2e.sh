#!/usr/bin/env bash
# End-to-end test for the whisper.cpp native layer.
#
# Builds whisper-cli from the submodule, downloads the tiny model if needed,
# and transcribes the bundled JFK sample to verify the pipeline works.
#
# Usage: ./tests/whisper-native-e2e.sh
# Exit code 0 = all tests pass.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WHISPER_DIR="$ROOT_DIR/whisper.cpp"
BUILD_DIR="$WHISPER_DIR/build-linux"
MODEL_DIR="$WHISPER_DIR/models"
MODEL_FILE="$MODEL_DIR/ggml-tiny.bin"
MODEL_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
SAMPLE_WAV="$WHISPER_DIR/samples/jfk.wav"

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); echo "  PASS: $1"; }
fail() { FAIL=$((FAIL + 1)); echo "  FAIL: $1"; }

echo "=== Whisper Native E2E Tests ==="
echo ""

# --- Build ---
echo "[1/5] Building whisper-cli..."
if [ ! -f "$BUILD_DIR/bin/whisper-cli" ]; then
    mkdir -p "$BUILD_DIR"
    cmake -S "$WHISPER_DIR" -B "$BUILD_DIR" \
        -DCMAKE_BUILD_TYPE=Release \
        -DWHISPER_BUILD_TESTS=OFF \
        -DWHISPER_BUILD_EXAMPLES=ON \
        -DGGML_OPENMP=OFF \
        > /dev/null 2>&1
    make -C "$BUILD_DIR" -j"$(nproc)" whisper-cli > /dev/null 2>&1
fi

if [ -f "$BUILD_DIR/bin/whisper-cli" ]; then
    pass "whisper-cli built"
else
    fail "whisper-cli build failed"
    exit 1
fi

# --- Model ---
echo "[2/5] Checking model..."
if [ ! -f "$MODEL_FILE" ]; then
    echo "       Downloading ggml-tiny.bin..."
    mkdir -p "$MODEL_DIR"
    curl -L -o "$MODEL_FILE" "$MODEL_URL" 2>/dev/null
fi

SIZE=$(stat -c%s "$MODEL_FILE" 2>/dev/null || stat -f%z "$MODEL_FILE" 2>/dev/null)
if [ "$SIZE" -gt 50000000 ]; then
    pass "ggml-tiny.bin present (${SIZE} bytes)"
else
    fail "ggml-tiny.bin too small (${SIZE} bytes, expected >50MB)"
    exit 1
fi

# --- Sample file ---
echo "[3/5] Checking test sample..."
if [ -f "$SAMPLE_WAV" ]; then
    pass "jfk.wav present"
else
    fail "jfk.wav missing"
    exit 1
fi

# --- Transcription (auto-detect language) ---
echo "[4/5] Transcribing with language=auto..."
OUTPUT=$("$BUILD_DIR/bin/whisper-cli" \
    -m "$MODEL_FILE" \
    -f "$SAMPLE_WAV" \
    -l auto \
    --no-timestamps \
    -t 2 \
    2>/dev/null)

if echo "$OUTPUT" | grep -qi "ask not what your country"; then
    pass "Transcription matches expected text (auto-detect)"
else
    fail "Transcription output unexpected: $OUTPUT"
fi

# --- Transcription (explicit English) ---
echo "[5/5] Transcribing with language=en..."
OUTPUT_EN=$("$BUILD_DIR/bin/whisper-cli" \
    -m "$MODEL_FILE" \
    -f "$SAMPLE_WAV" \
    -l en \
    --no-timestamps \
    -t 2 \
    2>/dev/null)

if echo "$OUTPUT_EN" | grep -qi "ask not what your country"; then
    pass "Transcription matches expected text (en)"
else
    fail "Transcription output unexpected: $OUTPUT_EN"
fi

# --- Summary ---
echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
