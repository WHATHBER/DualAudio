#!/bin/bash
#
# DualAudio 构建脚本（无需 Gradle，纯命令行工具链）
#
# 依赖：
#   - JDK 11+
#   - Android SDK：build-tools（含 aapt2 / zipalign / apksigner / d8）+ platforms
#
# 用法：
#   export ANDROID_HOME=/path/to/android-sdk
#   ./build.sh
#
# 产物：out/DualAudio.apk
#
set -e

APP="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
OUT="$APP/out"

: "${ANDROID_HOME:?请先设置 ANDROID_HOME 指向 Android SDK 目录}"

BT=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)
AJ=$(ls -d "$ANDROID_HOME"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)
[ -d "$BT" ] || { echo "[x] 找不到 build-tools"; exit 1; }
[ -f "$AJ" ] || { echo "[x] 找不到 android.jar"; exit 1; }

echo "build-tools : $BT"
echo "android.jar : $AJ"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/core" "$OUT/coredex" "$APP/assets"

echo "=== 1/8 编译 App 代码 ==="
find "$APP/src" -name "*.java" > "$OUT/sources.txt"
javac -source 8 -target 8 -nowarn -cp "$AJ" -d "$OUT/classes" @"$OUT/sources.txt"

echo "=== 2/8 生成 classes.dex ==="
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$AJ" --output "$OUT/dex" $(find "$OUT/classes" -name "*.class")

echo "=== 3/8 编译逻辑层 core/MultiAudio.java ==="
javac -source 8 -target 8 -nowarn -d "$OUT/core" "$ROOT/core/MultiAudio.java"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$AJ" --output "$OUT/coredex" "$OUT/core/MultiAudio.class"
cp "$OUT/coredex/classes.dex" "$APP/assets/MultiAudio.dex"

echo "=== 4/8 编译资源 ==="
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "=== 5/8 链接资源与清单 ==="
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" \
  --manifest "$APP/AndroidManifest.xml" -A "$APP/assets" \
  "$OUT/res.zip" \
  --min-sdk-version 26 --target-sdk-version 34

echo "=== 6/8 并入 classes.dex ==="
(cd "$OUT/dex" && zip -q -u "$OUT/base.apk" classes.dex)

echo "=== 7/8 对齐 ==="
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "=== 8/8 签名 ==="
# 本地默认自签名；CI 环境下从 Secrets 还原正式密钥，保证与已发布版本同签名
KS="$APP/release.keystore"
KSPASS="${KEYSTORE_PASSWORD:-dualaudio}"
ALIAS="${KEY_ALIAS:-dualaudio}"
KEYPASS="${KEY_PASSWORD:-dualaudio}"

if [ -n "$KEYSTORE_BASE64" ]; then
  echo "    使用 CI Secrets 提供的签名密钥"
  KS="$OUT/release.keystore"
  printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$KS"
elif [ ! -f "$KS" ]; then
  echo "    生成本地自签名密钥（仅供测试，切勿提交）"
  keytool -genkeypair -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KSPASS" -keypass "$KEYPASS" \
    -dname "CN=DualAudio, O=Self, C=CN" >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$KSPASS" --key-pass "pass:$KEYPASS" \
  --out "$OUT/DualAudio.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/DualAudio.apk"

echo
echo "=== 完成：$OUT/DualAudio.apk ==="
