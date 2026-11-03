#!/usr/bin/env bash
# 无 Gradle 构建脚本（Linux / GitHub Actions）。
# 与 app/*/build.ps1 等价，但最低支持 Android 4.4（API 19）。
#
# 用法：bash scripts/build_app.sh DafeiyuBoot [minSdk]
set -euo pipefail

APP="${1:-}"
MIN_SDK="${2:-19}"
TARGET_SDK="${TARGET_SDK:-34}"
SIGN_SDK="${SIGN_SDK:-18}"   # 只影响 v1 签名摘要算法：18 及以下用 SHA-1，最老的校验器也认

if [ -z "$APP" ]; then
  echo "usage: build_app.sh <DafeiyuBoot|DafeiyuChat> [minSdk]" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPDIR="$ROOT/app/$APP"
BUILD="$APPDIR/build"
DIST="$ROOT/dist"

if [ ! -d "$APPDIR" ]; then
  echo "app not found: $APPDIR" >&2
  exit 2
fi

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
if [ -z "${ANDROID_HOME:-}" ]; then
  echo "ANDROID_HOME / ANDROID_SDK_ROOT is not set" >&2
  exit 2
fi

pick_dir() {
  local pattern="$1"
  # shellcheck disable=SC2086
  ls -d $pattern 2>/dev/null | sort -V | tail -1
}

BT_DIR="$(pick_dir "$ANDROID_HOME/build-tools/*")"
PLATFORM_JAR="$(pick_dir "$ANDROID_HOME/platforms/android-*/android.jar")"
if [ -z "${BT_DIR:-}" ] || [ -z "${PLATFORM_JAR:-}" ]; then
  echo "Android SDK incomplete: build-tools or platform not found under $ANDROID_HOME" >&2
  exit 2
fi

AAPT2="$BT_DIR/aapt2"
ZIPALIGN="$BT_DIR/zipalign"
APKSIGNER="$BT_DIR/apksigner"
D8="$BT_DIR/d8"
LAMBDA_STUBS="$BT_DIR/core-lambda-stubs.jar"

for tool in "$AAPT2" "$ZIPALIGN" "$APKSIGNER" "$D8" "$LAMBDA_STUBS"; do
  if [ ! -e "$tool" ]; then
    echo "missing build tool: $tool" >&2
    exit 2
  fi
done

# 每个 app 一个独立签名，保证同一台手机上可重复升级安装
KS="$ROOT/keystore/$APP.jks"
ALIAS="$APP"
PASS="dafeiyu-phone"
mkdir -p "$ROOT/keystore"
if [ ! -f "$KS" ]; then
  echo "[0/7] generate keystore $KS"
  keytool -genkeypair -keystore "$KS" -alias "$ALIAS" \
    -storepass "$PASS" -keypass "$PASS" \
    -keyalg RSA -keysize 2048 -validity 20000 \
    -dname "CN=Dafeiyu, OU=Phone, O=Dafeiyu, L=CN, C=CN" >/dev/null 2>&1
fi

VERSION_NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APPDIR/AndroidManifest.xml" | head -1)"
VERSION_CODE="$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$APPDIR/AndroidManifest.xml" | head -1)"
VERSION_NAME="${VERSION_NAME:-1.0}"
VERSION_CODE="${VERSION_CODE:-1}"

echo "== $APP  minSdk=$MIN_SDK targetSdk=$TARGET_SDK version=$VERSION_NAME($VERSION_CODE)"
echo "== build-tools: $BT_DIR"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "[1/7] compile resources"
"$AAPT2" compile --dir "$APPDIR/res" -o "$BUILD/res.zip"

ASSET_FLAG=()
if [ -d "$APPDIR/assets" ]; then
  ASSET_FLAG=(-A "$APPDIR/assets")
fi

echo "[2/7] link resources + assets"
"$AAPT2" link \
  -o "$BUILD/app-unsigned.apk" \
  -I "$PLATFORM_JAR" \
  --manifest "$APPDIR/AndroidManifest.xml" \
  "${ASSET_FLAG[@]}" \
  --auto-add-overlay \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  "$BUILD/res.zip"

echo "[3/7] compile java (source/target 8, desugar for API $MIN_SDK)"
mapfile -t SOURCES < <(find "$APPDIR/src" -name '*.java' | sort)
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$PLATFORM_JAR:$LAMBDA_STUBS" \
  -d "$BUILD/classes" \
  "${SOURCES[@]}"

echo "[4/7] d8"
( cd "$BUILD/classes" && jar cf "$BUILD/classes.jar" . )
"$D8" --lib "$PLATFORM_JAR" --min-api "$MIN_SDK" --output "$BUILD/dex" "$BUILD/classes.jar"

echo "[5/7] add classes.dex"
( cd "$BUILD/dex" && jar uf "$BUILD/app-unsigned.apk" classes.dex )

echo "[6/7] zipalign"
"$ZIPALIGN" -f -p 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"
"$ZIPALIGN" -c -p 4 "$BUILD/app-aligned.apk"

echo "[7/7] sign (v1 + v2, v1 digest for API $SIGN_SDK)"
OUT="$DIST/$APP.apk"
"$APKSIGNER" sign \
  --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --min-sdk-version "$SIGN_SDK" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --out "$OUT" \
  "$BUILD/app-aligned.apk"
"$APKSIGNER" verify --min-sdk-version "$MIN_SDK" "$OUT"

echo "== badging"
"$AAPT2" dump badging "$OUT" | sed -n '1,3p'
echo "== done: $OUT ($(wc -c <"$OUT") bytes)"
