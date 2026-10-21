#!/usr/bin/env bash
# 构建「荣耀 SystemUI 增强」LSPosed 模块 APK（Linux，免 Gradle）
# 依赖：JDK 17+、kotlinc、Android SDK build-tools(35+) 的 aapt2/d8/zipalign/apksigner
set -euo pipefail

BASE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${TOOLS:-$BASE/../tools}"
BT="${BT:-$TOOLS/android-35}"
ANDROID_JAR="${ANDROID_JAR:-$TOOLS/android-34-ext12/android.jar}"
KOTLINC="${KOTLINC:-$TOOLS/kotlinc/bin/kotlinc}"
API_JAR="$TOOLS/api/classes.jar"
SVC_JAR="$TOOLS/svc/classes.jar"
IFC_JAR="$TOOLS/ifc/classes.jar"
KOTLIN_STDLIB="$TOOLS/kotlinc/lib/kotlin-stdlib.jar"
BUILD="$BASE/build"
KS="$BASE/ks.jks"
KS_PASS="honorduobar"

# 每次打包自动递增版本号：versionCode +1、versionName 补丁位 +1
MANIFEST="$BASE/AndroidManifest.xml"
VC="$(grep -oP 'android:versionCode="\K[0-9]+' "$MANIFEST" | head -1)"
VN="$(grep -oP 'android:versionName="\K[0-9.]+' "$MANIFEST" | head -1)"
[ -n "$VC" ] && [ -n "$VN" ] || { echo "ERROR: 无法从 AndroidManifest.xml 解析版本号"; exit 1; }
NEW_VC=$((VC + 1))
IFS='.' read -r VN_MAJOR VN_MINOR VN_PATCH <<< "$VN"
NEW_VN="${VN_MAJOR:-1}.${VN_MINOR:-0}.$(( ${VN_PATCH:-0} + 1 ))"
sed -i "s/android:versionCode=\"$VC\"/android:versionCode=\"$NEW_VC\"/" "$MANIFEST"
sed -i "s/android:versionName=\"$VN\"/android:versionName=\"$NEW_VN\"/" "$MANIFEST"
APK_NAME="HonorDuoBar-$NEW_VN.apk"
echo "版本: $VN($VC) -> $NEW_VN($NEW_VC)"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex"

echo "== 1/6 编译 Kotlin =="
"$KOTLINC" "$BASE/src" \
  -classpath "$ANDROID_JAR:$API_JAR:$SVC_JAR:$IFC_JAR" \
  -jvm-target 1.8 -nowarn -d "$BUILD/classes"

echo "== 2/6 编译资源 (aapt2 compile) =="
"$BT/aapt2" compile --dir "$BASE/res" -o "$BUILD/res.zip"

echo "== 3/6 链接资源 (aapt2 link) =="
"$BT/aapt2" link \
  -o "$BUILD/unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BASE/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  "$BUILD/res.zip"

echo "== 4/6 生成 dex (d8，含 libxposed-service 与 kotlin-stdlib) =="
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$BUILD/dex" \
  $(cat "$BUILD/classes.txt") "$SVC_JAR" "$IFC_JAR" "$KOTLIN_STDLIB"

echo "== 5/6 注入 classes.dex / META-INF/xposed 并 zipalign =="
python3 - "$BUILD/unsigned.apk" "$BUILD/dex/classes.dex" "$BASE/META-INF/xposed" <<'PY'
import sys, zipfile, shutil, os
apk, dex, meta = sys.argv[1], sys.argv[2], sys.argv[3]
tmp = apk + ".tmp"
zin = zipfile.ZipFile(apk, "r")
zout = zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    if item.filename == "classes.dex":
        continue
    zout.writestr(item, zin.read(item.filename))
zin.close()
zout.write(dex, "classes.dex")
for name in ("java_init.list", "module.prop", "scope.list"):
    zout.write(os.path.join(meta, name), "META-INF/xposed/" + name)
zout.close()
shutil.move(tmp, apk)
PY
"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "== 6/6 签名 (apksigner) =="
if [ ! -f "$KS" ]; then
  keytool -genkeypair -alias honorduobar -keyalg RSA -keysize 2048 -validity 10000 \
    -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=HonorSystemUI, OU=Dev, O=Dev, L=CN, S=CN, C=CN"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$BUILD/$APK_NAME" "$BUILD/aligned.apk"
"$BT/apksigner" verify "$BUILD/$APK_NAME"

echo ""
echo "BUILD OK: $BUILD/$APK_NAME"
