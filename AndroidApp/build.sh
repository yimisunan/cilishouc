#!/bin/bash
# 磁力收藏夹 正式版构建脚本（Java + Android View，零第三方依赖）
# 工具链：Debian arm64 aapt2 + build-tools 29.0.3 + JDK17 javac + D8(r8.jar) + apksigner
set -u

PROJ="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SDK="${MV_ANDROID_SDK:-/opt/androidsdk}"
BT="${MV_BUILD_TOOLS:-/usr/lib/android-sdk/build-tools/29.0.3}"
JAR="$SDK/android-30.jar"
OUT="$PROJ/build"
APK="${MV_APK:-$PROJ/MagnetVault.apk}"
KS="${MV_KEYSTORE:-$PROJ/release.keystore}"
KS_ALIAS="${MV_KEY_ALIAS:-magnetvault}"

if [ ! -f "$KS" ]; then
  echo "FAIL: 签名密钥不存在，请设置 MV_KEYSTORE 或提供 release.keystore"
  exit 1
fi
if [ -z "${MV_STORE_PASSWORD:-}" ]; then
  if [ -t 0 ]; then
    read -r -s -p "签名密钥库口令: " MV_STORE_PASSWORD || exit 1
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
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" || exit 1

echo "=== 1. 编译资源 ==="
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip" || { echo "FAIL: aapt2 compile"; exit 1; }

echo "=== 2. 链接资源 + R.java ==="
"$BT/aapt2" link -o "$OUT/base-unsigned.apk" \
  -I "$JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --warn-manifest-validation \
  "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 9 \
  --version-name "2.3.1" || { echo "FAIL: aapt2 link"; exit 1; }

echo "=== 3. javac ==="
mapfile -d '' SOURCES < <(find "$PROJ/src" "$OUT/gen" -name '*.java' -print0)
javac -nowarn --release 8 -classpath "$JAR" \
  -d "$OUT/classes" "${SOURCES[@]}" \
  || { echo "FAIL: javac"; exit 1; }

 echo "=== 4. dex ==="
mapfile -d '' CLASSES < <(find "$OUT/classes" -name '*.class' -print0)
java -cp "$SDK/r8.jar" com.android.tools.r8.D8 --lib "$JAR" --min-api 26 \
  --output "$OUT/dex" "${CLASSES[@]}" \
  || { echo "FAIL: d8"; exit 1; }

echo "=== 5. 打包 dex ==="
cp "$OUT/base-unsigned.apk" "$OUT/unsigned.apk" || exit 1
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex) || { echo "FAIL: zip"; exit 1; }

echo "=== 6. 对齐 ==="
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk" || { echo "FAIL: zipalign"; exit 1; }

echo "=== 7. 签名 ==="
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass env:MV_STORE_PASSWORD --key-pass env:MV_KEY_PASSWORD \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk" \
  || { echo "FAIL: apksigner"; exit 1; }

"$BT/apksigner" verify "$APK" || exit 1
echo "=== 8. 结果 ==="
ls -la "$APK"
"$BT/aapt" dump badging "$APK" 2>/dev/null | head -4
echo "BUILD_OK"
echo "安装：cp \"$APK\" /data/local/tmp/mv.apk && pm install -r -t /data/local/tmp/mv.apk"