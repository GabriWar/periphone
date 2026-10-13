#!/usr/bin/env bash
# Compila o APK sem gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
JAVAC=/usr/lib/jvm/java-21-openjdk/bin/javac
OUT=usb-audio-bridge.apk
B=build

rm -rf "$B"; mkdir -p "$B"/{res,gen,classes,dex}

"$BT/aapt2" compile --dir res -o "$B/res"
"$BT/aapt2" link -o "$B/base.apk" -I "$AJ" --manifest AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code 9 --version-name 1.8 \
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
