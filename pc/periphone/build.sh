#!/usr/bin/env bash
# Builds periphone: one static x86-64 Linux binary (libopus linked in, no runtime deps but the
# sound server; adb / ffmpeg only for USB / camera). Needs Go 1.24+, a C compiler and cmake.
set -euo pipefail
cd "$(dirname "$0")"

OPUS_VER=1.6.1
OPUS_SHA=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
C=.opus
if [ ! -f "$C/libopus.a" ]; then
    mkdir -p "$C"
    curl -fsSL -o "$C/opus.tar.gz" "https://downloads.xiph.org/releases/opus/opus-$OPUS_VER.tar.gz"
    echo "$OPUS_SHA  $C/opus.tar.gz" | sha256sum -c --quiet - || { rm -f "$C/opus.tar.gz"; exit 1; }
    tar -xzf "$C/opus.tar.gz" -C "$C" && rm "$C/opus.tar.gz"
    cmake -S "$C/opus-$OPUS_VER" -B "$C/build" -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF \
        -DOPUS_DRED=OFF -DOPUS_OSCE=OFF >/dev/null
    cmake --build "$C/build" -j >/dev/null
    cp "$C/build/libopus.a" "$C/"
    mkdir -p "$C/include" && cp "$C/opus-$OPUS_VER"/include/*.h "$C/include/"
fi

# static: runs on any x86-64 Linux, whatever its libc. netgo/osusergo: no glibc NSS needed.
CGO_ENABLED=1 go build -trimpath -tags 'netgo osusergo' \
    -ldflags '-s -w -linkmode external -extldflags "-static"' -o periphone .
echo "ok: $(pwd)/periphone ($(stat -c %s periphone) bytes)"
