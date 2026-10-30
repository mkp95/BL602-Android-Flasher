#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
SDK="${ANDROID_HOME}"
PLATFORM="${ANDROID_PLATFORM:-android-35}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-35.0.0}"
MIN_API=23
TARGET_API=28
VERSION_CODE=1
VERSION_NAME="0.1.0"
PLATFORM_DIR="$SDK/platforms/$PLATFORM"
BT="$SDK/build-tools/$BUILD_TOOLS"
ANDROID_JAR="$PLATFORM_DIR/android.jar"
for f in "$ANDROID_JAR" "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner"; do
  [[ -e "$f" ]] || { echo "Missing Android SDK component: $f" >&2; exit 2; }
done
WORK="build"
GEN="$WORK/generated"
CLS="$WORK/classes"
DEX="$WORK/dex"
RES="$WORK/resources"
mkdir -p "$GEN" "$CLS" "$DEX" "$RES" "$WORK/deps"
echo "Downloading usb-serial-for-android AAR..."
AAR="$WORK/deps/usb-serial-for-android-3.8.1.aar"
curl -fL --retry 3 "https://jitpack.io/com/github/mik3y/usb-serial-for-android/3.8.1/usb-serial-for-android-3.8.1.aar" -o "$AAR"
unzip -p "$AAR" classes.jar > "$WORK/deps/usb-serial.jar"
echo "Compiling Android resources..."
"$BT/aapt2" compile --dir src/res -o "$RES/resources.zip"
echo "Linking manifest and resources..."
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest src/AndroidManifest.xml --java "$GEN" --min-sdk-version "$MIN_API" --target-sdk-version "$TARGET_API" --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --auto-add-overlay -o "$WORK/unsigned-unaligned.apk" "$RES/resources.zip"
echo "Compiling Java sources..."
find src/java "$GEN" -name '*.java' -print > "$WORK/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -classpath "$ANDROID_JAR:$WORK/deps/usb-serial.jar" -d "$CLS" @"$WORK/sources.txt"
echo "Converting Java bytecode to DEX..."
"$BT/d8" --lib "$ANDROID_JAR" --min-api "$MIN_API" --classpath "$WORK/deps/usb-serial.jar" --output "$DEX" $(find "$CLS" -name '*.class' -print) "$WORK/deps/usb-serial.jar"
echo "Packaging DEX into APK..."
mkdir -p "$WORK/apk"
cp "$WORK/unsigned-unaligned.apk" "$WORK/apk/base.apk"
(cd "$WORK/apk" && unzip -oq base.apk && rm -f base.apk && cp ../dex/classes.dex . && zip -q -r ../unsigned.apk .)
"$BT/zipalign" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
KEYSTORE="$WORK/debug.keystore"
if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair -v -keystore "$KEYSTORE" -storepass android -keypass android -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --out "$WORK/BlFlashDroid-debug.apk" "$WORK/aligned.apk"
"$BT/apksigner" verify "$WORK/BlFlashDroid-debug.apk"
mkdir -p dist
cp "$WORK/BlFlashDroid-debug.apk" dist/
echo "APK ready: dist/BlFlashDroid-debug.apk"
