#!/bin/bash
# 磁力收藏夹：编译、对齐并使用本地密钥签名。
set -euo pipefail

PROJ="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SDK="${MV_ANDROID_SDK:-/opt/androidsdk}"
BT="${MV_BUILD_TOOLS:-/usr/lib/android-sdk/build-tools/29.0.3}"
JAR="${MV_ANDROID_JAR:-$SDK/android-30.jar}"
R8="${MV_R8_JAR:-$SDK/r8.jar}"
OUT="$PROJ/build"
APK="${MV_APK:-$PROJ/MagnetVault.apk}"
KS="${MV_KEYSTORE:-$PROJ/release.keystore}"
KS_ALIAS="${MV_KEY_ALIAS:-magnetvault}"
VERSION_NAME="${MV_VERSION_NAME:-$(sed -n 's/^versionName=//p' "$PROJ/version.properties")}"
VERSION_CODE="${MV_VERSION_CODE:-$(sed -n 's/^versionCode=//p' "$PROJ/version.properties")}"

if [[ ! "$VERSION_NAME" =~ ^[0-9][A-Za-z0-9._+-]*$ ]] ||
   [[ ! "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] ||
   (( VERSION_CODE > 2100000000 )); then
  echo "FAIL: 应用版本号或 versionCode 无效"
  exit 1
fi
for file in "$JAR" "$KS"; do
  if [ ! -f "$file" ]; then
    echo "FAIL: 所需文件不存在：$file"
    exit 1
  fi
done
for tool in aapt2 zipalign apksigner; do
  if [ ! -x "$BT/$tool" ]; then
    echo "FAIL: 构建工具不存在或不可执行：$BT/$tool"
    exit 1
  fi
done
if [ ! -x "$BT/d8" ] && [ ! -f "$R8" ]; then
  echo "FAIL: 需要构建工具中的 d8，或设置 MV_R8_JAR"
  exit 1
fi
if [ -z "${MV_STORE_PASSWORD:-}" ]; then
  if [ -t 0 ]; then
    read -r -s -p "签名密钥库口令: " MV_STORE_PASSWORD
    echo
  else
    echo "FAIL: 请通过 MV_STORE_PASSWORD 提供签名口令，或在交互终端运行"
    exit 1
  fi
fi
MV_KEY_PASSWORD="${MV_KEY_PASSWORD:-$MV_STORE_PASSWORD}"
export MV_STORE_PASSWORD MV_KEY_PASSWORD

echo "=== 0. 清理 ==="
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$(dirname -- "$APK")"

echo "=== 1. 编译资源 ==="
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "=== 2. 链接资源 + R.java ==="
"$BT/aapt2" link -o "$OUT/base-unsigned.apk" \
  -I "$JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --warn-manifest-validation \
  "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME"

echo "=== 3. javac ==="
mapfile -d '' SOURCES < <(find "$PROJ/src" "$OUT/gen" -name '*.java' -print0)
javac -nowarn --release 8 -classpath "$JAR" \
  -d "$OUT/classes" "${SOURCES[@]}"

echo "=== 4. dex ==="
mapfile -d '' CLASSES < <(find "$OUT/classes" -name '*.class' -print0)
if [ -x "$BT/d8" ]; then
  "$BT/d8" --lib "$JAR" --min-api 26 \
    --output "$OUT/dex" "${CLASSES[@]}"
else
  java -cp "$R8" com.android.tools.r8.D8 --lib "$JAR" --min-api 26 \
    --output "$OUT/dex" "${CLASSES[@]}"
fi

echo "=== 5. 打包 dex ==="
cp "$OUT/base-unsigned.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)

echo "=== 6. 对齐 ==="
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "=== 7. 签名 ==="
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass env:MV_STORE_PASSWORD --key-pass env:MV_KEY_PASSWORD \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk"

"$BT/apksigner" verify "$APK"
echo "=== 8. 结果 ==="
ls -la "$APK"
if [ -x "$BT/aapt" ]; then
  "$BT/aapt" dump badging "$APK" | sed -n '1,4p'
fi
echo "BUILD_OK"
echo "安装：cp \"$APK\" /data/local/tmp/mv.apk && pm install -r -t /data/local/tmp/mv.apk"
