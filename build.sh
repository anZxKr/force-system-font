#!/bin/sh
#
#   sh build.sh            debug build   (module logging on)
#   sh build.sh release    release build (module logging off)
#
# Needs: javac (JDK 17+), keytool, aapt, zipalign, apksigner, d8 (or r8.jar),
#        android.jar (API 34+) and libs/api-82.jar (Xposed API, compile only).
#
# Optional env vars:
#   ANDROID_JAR  (default ~/android/android-34/android.jar)
#   XPOSED_JAR   (default libs/api-82.jar)
#   R8_JAR       (default ~/tools/r8.jar, used when there is no d8 command)
#   KEYSTORE     your own key (default debug.jks, created on first run)
#   KEYSTORE_PASS (default android)   ALIAS (default fsf)   KEY_PASS (default = KEYSTORE_PASS)
set -eu
cd "$(dirname "$0")"

die() { echo "error: $*" >&2; exit 1; }

BUILD_TYPE="${1:-debug}"
case "$BUILD_TYPE" in
  debug)   DEBUG=true;  JAVAC_G="-g";      D8_MODE="--debug" ;;
  release) DEBUG=false; JAVAC_G="-g:none"; D8_MODE="--release" ;;
  *) die "usage: sh build.sh [debug|release]" ;;
esac

ANDROID_JAR="${ANDROID_JAR:-$HOME/android/android-34/android.jar}"
XPOSED_JAR="${XPOSED_JAR:-libs/api-82.jar}"
R8_JAR="${R8_JAR:-$HOME/tools/r8.jar}"
KEYSTORE="${KEYSTORE:-debug.jks}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
ALIAS="${ALIAS:-fsf}"
KEY_PASS="${KEY_PASS:-$KEYSTORE_PASS}"
export KEYSTORE_PASS KEY_PASS   # passed to keytool/apksigner by name

for tool in javac keytool aapt zipalign apksigner; do
  command -v "$tool" >/dev/null 2>&1 || die "missing tool: $tool"
done
[ -f "$ANDROID_JAR" ] || die "missing android.jar: $ANDROID_JAR"
[ -f "$XPOSED_JAR" ]  || die "missing Xposed API jar: $XPOSED_JAR"

if command -v d8 >/dev/null 2>&1; then
  d8() { command d8 "$@"; }
elif [ -f "$R8_JAR" ]; then
  d8() { java -cp "$R8_JAR" com.android.tools.r8.D8 "$@"; }
else
  die "no d8 command and no r8.jar at $R8_JAR"
fi


prop() { sed -n "s/^$1=//p" gradle.properties; }
APP_ID="$(prop APP_ID)"; VERSION_NAME="$(prop VERSION_NAME)"; VERSION_CODE="$(prop VERSION_CODE)"
MIN_SDK="$(prop MIN_SDK)"; TARGET_SDK="$(prop TARGET_SDK)"
for v in "$APP_ID" "$VERSION_NAME" "$VERSION_CODE" "$MIN_SDK" "$TARGET_SDK"; do
  [ -n "$v" ] || die "gradle.properties is missing APP_ID / VERSION_* / *_SDK"
done

MAIN=app/src/main
OUT="out/ForceSystemFont-v$VERSION_NAME-$BUILD_TYPE.apk"

rm -rf out
mkdir -p out/classes out/dex out/gen

if [ ! -f "$KEYSTORE" ]; then
  echo "creating key: $KEYSTORE (keep a backup, updates must use the same key)"
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$ALIAS" -keyalg RSA -keysize 2048 \
    -validity 36500 -storepass:env KEYSTORE_PASS -keypass:env KEY_PASS \
    -dname "CN=ForceSystemFont"
fi


GEN_PKG_DIR="out/gen/$(echo "$APP_ID" | tr . /)"
mkdir -p "$GEN_PKG_DIR"
printf 'package %s;\n\npublic final class BuildConfig {\n    public static final boolean DEBUG = %s;\n    public static final String APPLICATION_ID = "%s";\n    public static final String BUILD_TYPE = "%s";\n    public static final int VERSION_CODE = %s;\n    public static final String VERSION_NAME = "%s";\n    private BuildConfig() {}\n}\n' \
  "$APP_ID" "$DEBUG" "$APP_ID" "$BUILD_TYPE" "$VERSION_CODE" "$VERSION_NAME" > "$GEN_PKG_DIR/BuildConfig.java"
sed "s#^<manifest xmlns:android=\"\([^\"]*\)\">#<manifest xmlns:android=\"\1\" package=\"$APP_ID\" android:versionCode=\"$VERSION_CODE\" android:versionName=\"$VERSION_NAME\">\n    <uses-sdk android:minSdkVersion=\"$MIN_SDK\" android:targetSdkVersion=\"$TARGET_SDK\" />#" \
  "$MAIN/AndroidManifest.xml" > out/AndroidManifest.xml
grep -q "package=\"$APP_ID\"" out/AndroidManifest.xml || die "could not prepare manifest (first line must be <manifest xmlns:android=\"...\">)"

echo "[1/5] compile ($BUILD_TYPE)"
javac --release 11 "$JAVAC_G" -Xlint:all -cp "$ANDROID_JAR:$XPOSED_JAR" -d out/classes \
  $(find "$MAIN/java" out/gen -name '*.java')

echo "[2/5] dex"
d8 "$D8_MODE" --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output out/dex \
  $(find out/classes -name '*.class')

echo "[3/5] pack manifest + assets"
aapt package -f -M out/AndroidManifest.xml -A "$MAIN/assets" -I "$ANDROID_JAR" -F out/base.apk
( cd out/dex && aapt add ../base.apk classes.dex >/dev/null )

echo "[4/5] align + sign"
zipalign -f -p 4 out/base.apk out/aligned.apk
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" \
  --ks-pass env:KEYSTORE_PASS --key-pass env:KEY_PASS \
  --v4-signing-enabled false --out "$OUT" out/aligned.apk

echo "[5/5] verify"
apksigner verify --min-sdk-version "$MIN_SDK" "$OUT"

echo "done: $(pwd)/$OUT"
