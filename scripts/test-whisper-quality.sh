#!/bin/bash
# Whisper transcription quality test — simulates the app's chunking pipeline.
#
# Replicates the exact same transcription strategy as the Android app:
#   1. Split audio into 10-second chunks (AudioDecoder.CHUNK_SECONDS = 10)
#   2. Greedy decoding, best_of=1 (whisper_jni.c params)
#   3. Pass accumulated transcription as initial_prompt to next chunk
#   4. Concatenate all chunk outputs
#
# Also runs a full-file baseline to show the impact of chunking.
#
# Prerequisites: cmake, C/C++ compiler, curl, python3
# No ffmpeg needed — whisper-cli decodes MP3/WAV/FLAC/OGG natively.
#
# Usage:
#   ./scripts/test-whisper-quality.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
WHISPER_SRC="$PROJECT_DIR/whisper.cpp"
BUILD_DIR="$PROJECT_DIR/build-whisper-test"
DATA_DIR="$BUILD_DIR/data"
CLI="$BUILD_DIR/build/bin/whisper-cli"
MODEL="$DATA_DIR/ggml-tiny-q5_1.bin"
NCPU=$(nproc 2>/dev/null || sysctl -n hw.logicalcpu 2>/dev/null || echo 4)

CHUNK_MS=10000     # 10s, matches AudioDecoder.CHUNK_SECONDS

# ── Check prerequisites ─────────────────────────────────────────────────────

for cmd in cmake curl python3; do
    if ! command -v "$cmd" > /dev/null 2>&1; then
        echo "ERROR: $cmd is required but not found"
        exit 1
    fi
done

# ── Reference texts (main content, no LibriVox intro/outro) ─────────────────
# Pre-normalized: lowercase, no punctuation, single-spaced.

read -r -d '' REF_EN << 'ENDREF' || true
two roads diverged in a yellow wood and sorry i could not travel both and be one traveler long i stood and looked down one as far as i could to where it bent in the undergrowth then took the other as just as fair and having perhaps the better claim because it was grassy and wanted wear though as for that the passing there had worn them really about the same and both that morning equally lay in leaves no step had trodden black oh i kept the first for another day yet knowing how way leads on to way i doubted if i should ever come back i shall be telling this with a sigh somewhere ages and ages hence two roads diverged in a wood and i i took the one less traveled by and that has made all the difference
ENDREF

read -r -d '' REF_DE << 'ENDREF' || true
es war einmal ein kleines mädchen dem war vater und mutter gestorben und es war so arm dass es kein kämmerchen mehr hatte darin zu wohnen und kein bettchen mehr hatte darin zu schlafen und endlich gar nichts mehr als die kleider auf dem leib und ein stückchen brot in der hand dass ihm ein mitleidiges herz geschenkt hatte es war aber gut und fromm und weil es so von aller welt verlassen war ging es im vertrauen auf den lieben gott hinaus ins feld da begegnete ihm ein armer mann der sprach ach gib mir etwas zu essen ich bin so hungrig es reichte ihm das ganze stückchen brot und sagte gott segne dirs und ging weiter da kam ein kind das jammerte und sprach es friert mich so an meinem kopfe schenk mir etwas womit ich ihn bedecken kann da tat es seine mütze ab und gab sie ihm und als es noch eine weile gegangen war kam wieder ein kind und hatte kein leibchen an und fror da gab es ihm seins und noch weiter da bat eins um ein röcklein das gab es auch von sich hin endlich gelangte es in einen wald und es war schon dunkel geworden da kam noch eins und bat um ein hemdlein und das fromme mädchen dachte es ist dunkle nacht da sieht dich niemand du kannst wohl dein hemd weggeben und zog das hemd ab und gab es auch noch hin und wie es so stand und gar nichts mehr hatte fielen auf einmal die sterne vom himmel und waren lauter blanke taler und ob es gleich sein hemdlein weggegeben so hatte es ein neues an und das war vom allerfeinsten linnen da sammelte es sich die taler hinein und war reich für sein lebtag
ENDREF

# Audio sources (LibriVox, public domain)
URL_EN="https://archive.org/download/short_poetry_001_librivox/road_not_taken_frost_ac.mp3"
URL_DE="https://archive.org/download/grimm_maerchen_1_librivox/grimm_132_sterntaler.mp3"

# Minimum word-recall scores (%). Conservative for tiny-q5_1 model.
THRESH_FULL_EN=70
THRESH_FULL_DE=55
THRESH_CHUNKED_EN=65
THRESH_CHUNKED_DE=50

# ── Helpers ──────────────────────────────────────────────────────────────────

normalize() {
    tr '[:upper:]' '[:lower:]' \
        | sed "s/[^a-zäöüßàáâèéêìíîòóôùúûñ0-9 ]/ /g" \
        | tr -s ' ' '\n' \
        | sed '/^$/d'
}

# Bag-of-words recall / precision / f1.
word_overlap() {
    local ref_file="$1" hyp_file="$2"
    python3 -c "
from collections import Counter
ref = open('$ref_file').read().split()
hyp = open('$hyp_file').read().split()
rc, hc = Counter(ref), Counter(hyp)
hits = sum((rc & hc).values())
prec = hits / max(len(hyp), 1) * 100
rec  = hits / max(len(ref), 1) * 100
f1   = 2 * prec * rec / max(prec + rec, 1)
print(f'{rec:.1f} {prec:.1f} {f1:.1f}')
"
}

# ── Build whisper-cli ────────────────────────────────────────────────────────

if [ ! -x "$CLI" ]; then
    echo "Building whisper-cli for host..."
    cmake -S "$WHISPER_SRC" -B "$BUILD_DIR/build" \
        -DCMAKE_BUILD_TYPE=Release \
        -DWHISPER_BUILD_EXAMPLES=ON \
        -DBUILD_SHARED_LIBS=OFF \
        > /dev/null 2>&1
    cmake --build "$BUILD_DIR/build" --target whisper-cli -j"$NCPU" > /dev/null 2>&1
fi

# ── Download model + audio ──────────────────────────────────────────────────

mkdir -p "$DATA_DIR"

if [ ! -f "$MODEL" ]; then
    echo "Downloading whisper tiny-q5_1 model (31 MB)..."
    curl -sL -o "$MODEL" \
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"
fi

if [ ! -f "$DATA_DIR/test_en.mp3" ]; then
    echo "Downloading English audio (Robert Frost — The Road Not Taken)..."
    curl -sL -o "$DATA_DIR/test_en.mp3" "$URL_EN"
fi
if [ ! -f "$DATA_DIR/test_de.mp3" ]; then
    echo "Downloading German audio (Grimm — Die Sterntaler)..."
    curl -sL -o "$DATA_DIR/test_de.mp3" "$URL_DE"
fi

# ── Transcribe ───────────────────────────────────────────────────────────────

# Flags matching whisper_jni.c: greedy, best_of=1, no timestamps
APP_FLAGS="--best-of 1 --beam-size 1 --no-timestamps"

echo ""
echo "═══════════════════════════════════════════"
echo "  Whisper Transcription Quality Test"
echo "  model: tiny-q5_1  chunks: ${CHUNK_MS}ms"
echo "═══════════════════════════════════════════"

failed=0

for lang in en de; do
    audio="$DATA_DIR/test_${lang}.mp3"
    ref_var="REF_$(echo "$lang" | tr '[:lower:]' '[:upper:]')"
    ref_text="${!ref_var}"
    echo "$ref_text" | normalize > "$DATA_DIR/ref_${lang}.txt"

    # ── Full-file baseline ──
    hyp_full=$("$CLI" -m "$MODEL" -f "$audio" $APP_FLAGS -l "$lang" 2>/dev/null)
    echo "$hyp_full" | normalize > "$DATA_DIR/hyp_full_${lang}.txt"

    scores=$(word_overlap "$DATA_DIR/ref_${lang}.txt" "$DATA_DIR/hyp_full_${lang}.txt")
    recall=${scores%% *}
    thresh_var="THRESH_FULL_$(echo "$lang" | tr '[:lower:]' '[:upper:]')"
    thresh="${!thresh_var}"
    status="PASS"
    if awk "BEGIN{exit(${recall} >= ${thresh})}"; then status="FAIL"; failed=1; fi

    echo ""
    echo "[$lang] Full file:      recall/prec/f1 = $scores  $status (>=${thresh}%)"

    # ── Chunked with prompt (simulates app pipeline) ──
    # Get audio duration in ms from whisper-cli stderr
    duration_line=$("$CLI" -m "$MODEL" -f "$audio" $APP_FLAGS -l "$lang" --print-progress 2>&1 1>/dev/null | grep 'processing' | head -1)
    total_samples=$(echo "$duration_line" | grep -oP '\(\K[0-9]+(?= samples)')
    # 16000 samples/sec → duration_ms
    if [ -n "$total_samples" ]; then
        duration_ms=$(( total_samples * 1000 / 16000 ))
    else
        # Fallback: assume ~120s (both clips are under 3 min)
        duration_ms=180000
    fi

    accumulated=""
    offset_ms=0
    while [ "$offset_ms" -lt "$duration_ms" ]; do
        prompt_flag=""
        if [ -n "$accumulated" ]; then
            prompt_flag="--prompt $accumulated"
        fi

        chunk_hyp=$("$CLI" -m "$MODEL" -f "$audio" $APP_FLAGS -l "$lang" \
            --offset-t "$offset_ms" --duration "$CHUNK_MS" \
            $prompt_flag 2>/dev/null)
        chunk_text=$(echo "$chunk_hyp" | xargs)

        if [ -n "$chunk_text" ]; then
            if [ -z "$accumulated" ]; then
                accumulated="$chunk_text"
            else
                accumulated="$accumulated $chunk_text"
            fi
        fi

        offset_ms=$((offset_ms + CHUNK_MS))
    done

    echo "$accumulated" | normalize > "$DATA_DIR/hyp_chunked_${lang}.txt"
    scores=$(word_overlap "$DATA_DIR/ref_${lang}.txt" "$DATA_DIR/hyp_chunked_${lang}.txt")
    recall=${scores%% *}
    thresh_var="THRESH_CHUNKED_$(echo "$lang" | tr '[:lower:]' '[:upper:]')"
    thresh="${!thresh_var}"
    status="PASS"
    if awk "BEGIN{exit(${recall} >= ${thresh})}"; then status="FAIL"; failed=1; fi

    echo "[$lang] Chunked+prompt: recall/prec/f1 = $scores  $status (>=${thresh}%)"
done

echo ""
echo "═══════════════════════════════════════════"
if [ "$failed" -eq 0 ]; then
    echo "  All quality checks PASSED"
else
    echo "  Some quality checks FAILED"
fi
echo "═══════════════════════════════════════════"

exit "$failed"
