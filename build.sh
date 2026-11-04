#!/usr/bin/env bash
# Builds the APK without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Picks the newest installed build-tools and platform (override with BUILD_TOOLS / PLATFORM).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[ -d "$SDK" ] || { echo "error: Android SDK not found at $SDK (set ANDROID_HOME)" >&2; exit 1; }

newest() { ls -1 "$1" 2>/dev/null | grep -E "$2" | sort -V | tail -1; }
BT_VER="${BUILD_TOOLS:-$(newest "$SDK/build-tools" '^[0-9.]+$')}"
PLAT="${PLATFORM:-$(newest "$SDK/platforms" '^android-[0-9]+$')}"
[ -n "$BT_VER" ] || { echo "error: no build-tools in $SDK/build-tools" >&2; exit 1; }
[ -n "$PLAT" ] || { echo "error: no platform in $SDK/platforms" >&2; exit 1; }
BT="$SDK/build-tools/$BT_VER"
AJ="$SDK/platforms/$PLAT/android.jar"

# JDK 17+ (javac --release 8 is enough for the code; d8 desugars lambdas).
if [ -n "${JAVA_HOME:-}" ]; then JAVAC="$JAVA_HOME/bin/javac"
elif [ -x /usr/lib/jvm/java-21-openjdk/bin/javac ]; then JAVAC=/usr/lib/jvm/java-21-openjdk/bin/javac
else JAVAC=javac; fi

OUT=usb-audio-bridge.apk
B=build
echo "build-tools $BT_VER, $PLAT, $("$JAVAC" -version 2>&1)"

rm -rf "$B"; mkdir -p "$B"/{res,gen,classes,dex}

# ---- native: libopus (Bluetooth codec) + JNI glue, cached in .cache/ (needs the NDK + cmake)
OPUS_VER=1.6.1
OPUS_SHA=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
ABIS="arm64-v8a armeabi-v7a"
NDK="${ANDROID_NDK:-$SDK/ndk/$(newest "$SDK/ndk" '^[0-9.]+$')}"
[ -f "$NDK/build/cmake/android.toolchain.cmake" ] \
    || { echo "error: Android NDK not found (sdkmanager 'ndk;27.1.12297006', or set ANDROID_NDK)" >&2; exit 1; }
CMAKE="$(ls -d "$SDK"/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -1 || true)"; CMAKE="${CMAKE:-cmake}"
C=.cache
mkdir -p "$C"
if [ ! -d "$C/opus-$OPUS_VER" ]; then
    curl -fsSL -o "$C/opus.tar.gz" "https://downloads.xiph.org/releases/opus/opus-$OPUS_VER.tar.gz"
    echo "$OPUS_SHA  $C/opus.tar.gz" | sha256sum -c --quiet - || { rm -f "$C/opus.tar.gz"; exit 1; }
    tar -xzf "$C/opus.tar.gz" -C "$C" && rm "$C/opus.tar.gz"
fi
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
for abi in $ABIS; do
    ob="$C/opus-build-$abi"
    if [ ! -f "$ob/libopus.a" ]; then
        "$CMAKE" -S "$C/opus-$OPUS_VER" -B "$ob" -G Ninja -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI="$abi" -DANDROID_PLATFORM=26 \
            -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF -DOPUS_DRED=OFF -DOPUS_OSCE=OFF >/dev/null
        "$CMAKE" --build "$ob" >/dev/null
    fi
    case $abi in arm64-v8a) tgt=aarch64-linux-android26 ;; armeabi-v7a) tgt=armv7a-linux-androideabi26 ;; esac
    mkdir -p "$B/lib/$abi"
    "$TC/clang" --target="$tgt" -O2 -fPIC -shared -I"$C/opus-$OPUS_VER/include" \
        -Wl,-z,max-page-size=16384 -Wl,--gc-sections -s \
        -o "$B/lib/$abi/libuabopus.so" jni/opus_jni.c "$ob/libopus.a" -lm
done

"$BT/aapt2" compile --dir res -o "$B/res"
"$BT/aapt2" link -o "$B/base.apk" -I "$AJ" --manifest AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code 10 --version-name 2.0 \
    --java "$B/gen" "$B"/res/*.flat

"$JAVAC" -encoding UTF-8 --release 8 -Xlint:all,-options -cp "$AJ" -d "$B/classes" \
    $(find src "$B/gen" -name '*.java')

"$BT/d8" --release --lib "$AJ" --min-api 26 --output "$B/dex" \
    $(find "$B/classes" -name '*.class')

(cd "$B/dex" && zip -q ../base.apk classes.dex)
(cd "$B" && zip -q -0 base.apk lib/*/*.so)   # stored: loaded straight from the APK
"$BT/zipalign" -f 4 "$B/base.apk" "$B/aligned.apk"

[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore \
    -storepass android -keypass android -alias debug -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=usb-audio-bridge" >/dev/null 2>&1

"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out "$OUT" "$B/aligned.apk"

echo "ok: $OUT ($(stat -c %s "$OUT") bytes)"
