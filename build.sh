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
"$BT/zipalign" -f 4 "$B/base.apk" "$B/aligned.apk"

[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore \
    -storepass android -keypass android -alias debug -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=usb-audio-bridge" >/dev/null 2>&1

"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out "$OUT" "$B/aligned.apk"

echo "ok: $OUT ($(stat -c %s "$OUT") bytes)"
