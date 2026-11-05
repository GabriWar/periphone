#!/usr/bin/env bash
# Release binaries: static Linux builds for x86-64, arm64 and 32-bit ARM (armv7, hard float) into
# dist/, cross-compiled with Zig (libc: musl, linked in, so they run on any distro).
# ZIG=path/to/zig if it isn't on PATH (default fallback: ~/.cache/periphone-tools/zig/zig).
set -euo pipefail
cd "$(dirname "$0")"

ZIG="${ZIG:-$(command -v zig || echo "$HOME/.cache/periphone-tools/zig/zig")}"
[ -x "$ZIG" ] || { echo "error: zig not found (https://ziglang.org/download, or set ZIG=)" >&2; exit 1; }

OPUS_VER=1.6.1
OPUS_SHA=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
C=.opus
if [ ! -d "$C/opus-$OPUS_VER" ]; then
    mkdir -p "$C"
    curl -fsSL -o "$C/opus.tar.gz" "https://downloads.xiph.org/releases/opus/opus-$OPUS_VER.tar.gz"
    echo "$OPUS_SHA  $C/opus.tar.gz" | sha256sum -c --quiet - || { rm -f "$C/opus.tar.gz"; exit 1; }
    tar -xzf "$C/opus.tar.gz" -C "$C" && rm "$C/opus.tar.gz"
fi
mkdir -p "$C/include" && cp "$C/opus-$OPUS_VER"/include/*.h "$C/include/"

mkdir -p dist
rm -f dist/periphone-linux-*
#        GOARCH  zig target                cmake processor  dist name
for t in "amd64  x86_64-linux-musl         x86_64           x86_64" \
         "arm64  aarch64-linux-musl        aarch64          arm64" \
         "arm    arm-linux-musleabihf      armv7            armv7"; do
    read -r goarch target proc name <<<"$t"
    W="$C/zig-$goarch"
    mkdir -p "$W"
    # cmake and cgo want a plain compiler path: tiny wrappers around "zig cc -target ..."
    printf '#!/bin/sh\nexec "%s" cc -target %s "$@"\n' "$ZIG" "$target" > "$W/cc"
    printf '#!/bin/sh\nexec "%s" ar "$@"\n' "$ZIG" > "$W/ar"
    printf '#!/bin/sh\nexec "%s" ranlib "$@"\n' "$ZIG" > "$W/ranlib"
    chmod +x "$W/cc" "$W/ar" "$W/ranlib"

    lib="$C/$goarch/libopus.a"
    if [ "$goarch" != "$(go env GOARCH)" ] || [ ! -f "$lib.zig" ]; then
        cmake -S "$C/opus-$OPUS_VER" -B "$W/build" -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR="$proc" \
            -DCMAKE_C_COMPILER="$(pwd)/$W/cc" -DCMAKE_AR="$(pwd)/$W/ar" -DCMAKE_RANLIB="$(pwd)/$W/ranlib" \
            -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF \
            -DOPUS_DRED=OFF -DOPUS_OSCE=OFF >/dev/null
        cmake --build "$W/build" -j >/dev/null
        mkdir -p "$C/$goarch"
        cp "$W/build/libopus.a" "$lib"
        touch "$lib.zig"   # this one is musl-built: release builds may reuse it
    fi

    out="dist/periphone-linux-$name"
    CGO_ENABLED=1 GOOS=linux GOARCH="$goarch" GOARM=7 CC="$(pwd)/$W/cc" \
        go build -trimpath -tags 'netgo osusergo' \
        -ldflags '-s -w -linkmode external -extldflags "-static"' -o "$out" .
    echo "ok: $out ($(stat -c %s "$out") bytes)"
done
# the native build (build.sh, system compiler) must not pick up a musl-built library
rm -f "$C/$(go env GOARCH)/libopus.a" "$C/$(go env GOARCH)/libopus.a.zig"
(cd dist && sha256sum periphone-linux-* > SHA256SUMS-linux)
