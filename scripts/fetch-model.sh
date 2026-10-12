#!/bin/sh
# Downloads the speech models Caw loads and puts them where the app looks.
#
#     scripts/fetch-model.sh              Moonshine base only (the APK carries only Moonshine now)
#     scripts/fetch-model.sh moonshine    the same
#     scripts/fetch-model.sh streaming    the streaming zipformer (fallback, not shipped since 2026-10-12)
#     scripts/fetch-model.sh all          both
#
# Streaming: app/src/main/assets/models/sherpa-streaming-en/
#         encoder.int8.onnx  decoder.int8.onnx  joiner.int8.onnx  tokens.txt
#   Model: sherpa-onnx-streaming-zipformer-en-2023-06-26 (k2-fsa streaming zipformer transducer,
#   English, chunk 16 / left context 128). The same model the sherpa-onnx Android demo uses
#   with modelType "zipformer2". The encoder and joiner are the int8 files. The decoder is int8
#   if the archive has one, otherwise the float one (it is tiny); the app's file name stays
#   decoder.int8.onnx either way.
#
# Moonshine: app/src/main/assets/models/moonshine-base-en/
#         preprocess.onnx  encode.int8.onnx  uncached_decode.int8.onnx  cached_decode.int8.onnx  tokens.txt
#   Model: sherpa-onnx-moonshine-base-en-int8 (Useful Sensors Moonshine base, English, offline).
#   About 287 MB. When this folder is present the app uses Moonshine; otherwise the streaming model,
#   which is only a fallback now: leave sherpa-streaming-en/ out of assets/models/ so the APK stays small.
#
# Both come from release tag "asr-models" on github.com/k2-fsa/sherpa-onnx.
set -eu

WHICH=${1:-moonshine}
case "$WHICH" in
    all|streaming|moonshine) ;;
    *) echo "usage: $0 [all|streaming|moonshine]" >&2; exit 2 ;;
esac

BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
ASSETS="$ROOT/app/src/main/assets/models"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# download_unpack <archive name>: leaves the archive's folder at $WORK/<name>/unpacked/<name>
download_unpack() {
    echo "Downloading $1 ..."
    mkdir -p "$WORK/$1/unpacked"
    curl -fL --progress-bar -o "$WORK/$1/model.tar.bz2" "$BASE/$1.tar.bz2"
    tar -xjf "$WORK/$1/model.tar.bz2" -C "$WORK/$1/unpacked"
}

pick() { # pick <folder> <patterns...>: first file matching the patterns, in order
    dir=$1
    shift
    for pattern in "$@"; do
        for f in $dir/$pattern; do
            if [ -f "$f" ]; then echo "$f"; return 0; fi
        done
    done
    echo "No file matching $* in $dir" >&2
    exit 1
}

fetch_streaming() {
    MODEL=sherpa-onnx-streaming-zipformer-en-2023-06-26
    download_unpack "$MODEL"
    SRC="$WORK/$MODEL/unpacked/$MODEL"
    DEST="$ASSETS/sherpa-streaming-en"

    ENCODER=$(pick "$SRC" 'encoder-*.int8.onnx')
    DECODER=$(pick "$SRC" 'decoder-*.int8.onnx' 'decoder-*.onnx')
    JOINER=$(pick "$SRC" 'joiner-*.int8.onnx')
    TOKENS=$(pick "$SRC" 'tokens.txt')

    mkdir -p "$DEST"
    cp "$ENCODER" "$DEST/encoder.int8.onnx"
    cp "$DECODER" "$DEST/decoder.int8.onnx"
    cp "$JOINER" "$DEST/joiner.int8.onnx"
    cp "$TOKENS" "$DEST/tokens.txt"

    echo "Copied into $DEST:"
    echo "  encoder.int8.onnx  <- $(basename "$ENCODER")"
    echo "  decoder.int8.onnx  <- $(basename "$DECODER")"
    echo "  joiner.int8.onnx   <- $(basename "$JOINER")"
    echo "  tokens.txt"
}

fetch_moonshine() {
    MODEL=sherpa-onnx-moonshine-base-en-int8
    download_unpack "$MODEL"
    SRC="$WORK/$MODEL/unpacked/$MODEL"
    DEST="$ASSETS/moonshine-base-en"

    mkdir -p "$DEST"
    for name in preprocess.onnx encode.int8.onnx uncached_decode.int8.onnx cached_decode.int8.onnx tokens.txt; do
        if [ ! -f "$SRC/$name" ]; then
            echo "No $name in $MODEL" >&2
            exit 1
        fi
        cp "$SRC/$name" "$DEST/$name"
    done
    echo "Copied into $DEST:"
    echo "  preprocess.onnx  encode.int8.onnx  uncached_decode.int8.onnx  cached_decode.int8.onnx  tokens.txt"
}

if [ "$WHICH" = all ] || [ "$WHICH" = streaming ]; then fetch_streaming; fi
if [ "$WHICH" = all ] || [ "$WHICH" = moonshine ]; then fetch_moonshine; fi
