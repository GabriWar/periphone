#!/usr/bin/env bash
# Builds periphone: one static x86-64 Linux binary (libopus linked in, no runtime deps but the
# sound server; adb / ffmpeg only for USB / camera). Needs Go 1.24+, a C compiler and cmake.
set -euo pipefail
cd "$(dirname "$0")"

OPUS_VER=1.6.1
OPUS_SHA=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
C=.opus
ARCH=$(go env GOARCH)
fetch_opus() {   # the source, once (checksum-pinned)
    [ -d "$C/opus-$OPUS_VER" ] && return
    mkdir -p "$C"
    curl -fsSL -o "$C/opus.tar.gz" "https://downloads.xiph.org/releases/opus/opus-$OPUS_VER.tar.gz"
    echo "$OPUS_SHA  $C/opus.tar.gz" | sha256sum -c --quiet - || { rm -f "$C/opus.tar.gz"; exit 1; }
    tar -xzf "$C/opus.tar.gz" -C "$C" && rm "$C/opus.tar.gz"
    mkdir -p "$C/include" && cp "$C/opus-$OPUS_VER"/include/*.h "$C/include/"
}
if [ ! -f "$C/$ARCH/libopus.a" ]; then
    fetch_opus
    cmake -S "$C/opus-$OPUS_VER" -B "$C/build-$ARCH" -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF \
        -DOPUS_DRED=OFF -DOPUS_OSCE=OFF >/dev/null
    cmake --build "$C/build-$ARCH" -j >/dev/null
    mkdir -p "$C/$ARCH" && cp "$C/build-$ARCH/libopus.a" "$C/$ARCH/"
fi

# static: runs on any Linux of this CPU type, whatever its libc. netgo/osusergo: no glibc NSS needed.
CGO_ENABLED=1 go build -trimpath -tags 'netgo osusergo' \
    -ldflags '-s -w -linkmode external -extldflags "-static"' -o periphone .
echo "ok: $(pwd)/periphone ($(stat -c %s periphone) bytes)"
