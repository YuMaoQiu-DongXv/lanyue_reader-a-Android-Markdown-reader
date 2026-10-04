#!/usr/bin/env bash
# =============================================================================
#  蓝阅 · 免 Gradle 构建脚本
#
#  流水线：aapt2 compile → aapt2 link(-A assets, --java, -I android.jar)
#          → javac --release 8 → d8 → 注入 classes.dex
#          → 修正 assets 条目名（Windows 上 aapt2 的坑，见下）
#          → zipalign -p 4 → keytool + apksigner → 自检
#
#  用法： bash build.sh            完整构建
#         bash build.sh clean      清理产物
#         bash build.sh check      只做产物审计（不重新构建）
#
#  实测结论（踩过的坑，改脚本前先读）：
#   1) d8.bat / apksigner.bat 会用 PATH 里的 java（本机是 Java 8），而 lib/d8.jar 是
#      class file 55，直接调 .bat 必崩；必须用 JDK 17 的 java 直接跑 jar。
#   2) Windows 上 aapt2 link -A 会把子目录 assets 写成反斜杠（assets/web\app.js），
#      而 AssetManager 只认正斜杠 —— 不修就是整个前端 404，且 badging/unzip 看不出异常。
#   3) classes.dex 用 `jar ufM <apk> -C <dexdir> classes.dex` 注入：必须 -C（否则条目名
#      变成 build/dex/classes.dex）且必须 M（否则塞进多余 MANIFEST.MF）；jar 会保留
#      resources.arsc 的 STORED 属性（targetSdk≥30 的硬要求）。
#   4) keystore 不能放 build/（每次 rm -rf build 会换密钥，导致 adb install -r 失败）。
#   5) 工程路径含空格，批量传参一律用相对路径（先 cd）。
# =============================================================================
set -uo pipefail

PROJECT_ROOT="${PROJECT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"

SDK_ROOT="${SDK_ROOT:-/d/Android/Sdk}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
PLATFORM_DIR_NAME="${PLATFORM_DIR_NAME:-android-36}"
JDK_HOME="${JDK_HOME:-/c/Program Files/Microsoft/jdk-17.0.10.7-hotspot}"

APP_PACKAGE="com.dsh.mdreader"
MIN_SDK=26
TARGET_SDK=36
VERSION_CODE=1
VERSION_NAME="1.0.0"
OUT_APK_NAME="lanyue-${VERSION_NAME}-release"

APP_DIR="$PROJECT_ROOT/app"
MANIFEST="$APP_DIR/AndroidManifest.xml"
RES_DIR="$APP_DIR/res"
ASSETS_DIR="$APP_DIR/assets"
JAVA_SRC_DIR="$APP_DIR/java"

BUILD_DIR="$PROJECT_ROOT/build"
DIST_DIR="$PROJECT_ROOT/dist"
RES_FLAT="$BUILD_DIR/res_flat"
GEN_DIR="$BUILD_DIR/gen"
CLASSES_DIR="$BUILD_DIR/classes"
DEX_DIR="$BUILD_DIR/dex"
UNSIGNED_APK="$BUILD_DIR/app-unsigned.apk"
ALIGNED_APK="$BUILD_DIR/app-aligned.apk"
FINAL_APK="$DIST_DIR/$OUT_APK_NAME.apk"

KEYSTORE="$PROJECT_ROOT/keystore/release.keystore"
KEY_PASS_FILE="$PROJECT_ROOT/keystore.local.txt"
KEY_ALIAS="lanyue"

BT_DIR="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK_ROOT/platforms/$PLATFORM_DIR_NAME/android.jar"
AAPT2="$BT_DIR/aapt2.exe"
ZIPALIGN="$BT_DIR/zipalign.exe"
D8_JAR="$BT_DIR/lib/d8.jar"
APKSIGNER_JAR="$BT_DIR/lib/apksigner.jar"
JAVA="$JDK_HOME/bin/java.exe"
JAVAC="$JDK_HOME/bin/javac.exe"
JAR="$JDK_HOME/bin/jar.exe"
KEYTOOL="$JDK_HOME/bin/keytool.exe"

log()  { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[33m  ! %s\033[0m\n' "$*"; }
die()  { printf '\033[31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- check 模式
if [ "${1:-}" = "clean" ]; then
  log "清理"; rm -rf "$BUILD_DIR" "$DIST_DIR"; ok "已清理"; exit 0
fi

audit() {
  local apk="$1"
  log "产物审计"
  [ -f "$apk" ] || die "找不到 APK：$apk"

  echo "--- 体积 ---"
  local bytes; bytes=$(stat -c %s "$apk")
  printf "  APK = %s 字节 = %.1f KB\n" "$bytes" "$((bytes / 1024))"
  echo "--- 权限（必须为空） ---"
  local perms; perms=$("$AAPT2" dump badging "$apk" 2>/dev/null | grep -c "^uses-permission" || true)
  if [ "$perms" = "0" ]; then ok "权限列表为空"; else
    "$AAPT2" dump badging "$apk" | grep "^uses-permission" | sed 's/^/    /'
    die "出现权限：$perms 条（本项目必须零权限）"
  fi
  echo "--- 包信息 ---"
  "$AAPT2" dump badging "$apk" 2>/dev/null | grep -E "^(package|sdkVersion|targetSdkVersion|application-label|launchable-activity)" | sed 's/^/  /'
  echo "--- 关键条目 ---"
  local listing; listing=$("$JAVA" -version >/dev/null 2>&1; unzip -l "$apk" 2>/dev/null || true)
  echo "$listing" | grep -q "classes.dex" && ok "classes.dex 在包内" || die "缺 classes.dex"
  echo "$listing" | grep -q "assets/web/index.html" && ok "assets/web/index.html 在包内" || die "assets 未打进包"
  local webcount; webcount=$(echo "$listing" | grep -c "assets/web/")
  ok "assets/web 条目数 = $webcount"
  if echo "$listing" | grep -q '\\\\'; then
    echo "$listing" | grep '\\\\' | head -5 | sed 's/^/    /'
    die "assets 条目名里出现反斜杠（aapt2 Windows 坑未修）"
  fi
  ok "assets 条目名无反斜杠"
  echo "--- 签名 ---"
  "$JAVA" -jar "$APKSIGNER_JAR" verify --min-sdk-version "$MIN_SDK" "$apk" >/dev/null 2>&1 \
    && ok "签名校验通过（v2/v3）" || die "签名校验失败"
  "$ZIPALIGN" -c -p 4 "$apk" >/dev/null 2>&1 && ok "4 字节对齐成立" || die "zipalign 校验失败"
  echo "--- 体积预算（目标 <1MB，天花板 1.5MB） ---"
  local kb=$((bytes / 1024))
  if [ "$kb" -lt 1024 ]; then ok "APK ${kb} KB —— 达标（<1MB）"
  elif [ "$kb" -lt 1536 ]; then warn "APK ${kb} KB —— 超过 1MB 但仍在 1.5MB 天花板内"
  else die "APK ${kb} KB —— 超过 1.5MB 天花板"; fi
}

if [ "${1:-}" = "check" ]; then
  audit "$FINAL_APK"
  exit $?
fi

# ---------------------------------------------------------------- 预检
log "预检工具链"
for f in "$AAPT2" "$ZIPALIGN" "$D8_JAR" "$APKSIGNER_JAR" "$ANDROID_JAR" "$JAVA" "$JAVAC" "$JAR" "$KEYTOOL" "$MANIFEST" "$RES_DIR" "$JAVA_SRC_DIR"; do
  [ -e "$f" ] || die "缺少：$f"
done
JVER="$("$JAVA" -version 2>&1 | head -1)"
case "$JVER" in *'"17'*|*'"18'*|*'"19'*|*'"2'*) ok "$JVER";; *) die "需要 JDK 17+，当前：$JVER";; esac
[ -f "/d/DSH program/mdreader/tools/jscheck.sh" ] && bash "$PROJECT_ROOT/tools/jscheck.sh" \
  "$ASSETS_DIR/web/app.js" "$ASSETS_DIR/web/pipeline.js" "$ASSETS_DIR/web/index.html" \
  || warn "跳过 JS 语法闸门"

log "清理旧产物"
rm -rf "$BUILD_DIR" "$DIST_DIR"
mkdir -p "$RES_FLAT" "$GEN_DIR" "$CLASSES_DIR" "$DEX_DIR" "$DIST_DIR"

# ---------------------------------------------------------------- 资源
log "步骤 1/8  aapt2 compile"
"$AAPT2" compile --dir "$RES_DIR" -o "$RES_FLAT/" || die "aapt2 compile 失败"
ok "$(find "$RES_FLAT" -name '*.flat' | wc -l) 个 .flat"

log "步骤 2/8  aapt2 link"
LINK_ARGS=( -o "$UNSIGNED_APK" -I "$ANDROID_JAR" --manifest "$MANIFEST" --java "$GEN_DIR"
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK"
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --auto-add-overlay --no-version-vectors )
[ -d "$ASSETS_DIR" ] && LINK_ARGS+=( -A "$ASSETS_DIR" )
( cd "$RES_FLAT" && "$AAPT2" link "${LINK_ARGS[@]}" ./*.flat ) || die "aapt2 link 失败"
R_JAVA="$GEN_DIR/$(echo "$APP_PACKAGE" | tr '.' '/')/R.java"
[ -f "$R_JAVA" ] || die "未生成 R.java（检查 manifest 的 package 属性）"
ok "未签名 APK $(stat -c %s "$UNSIGNED_APK") 字节 + R.java"

# ---------------------------------------------------------------- 编译
log "步骤 3/8  javac --release 8"
mapfile -t SRC_FILES < <(find "$JAVA_SRC_DIR" -name '*.java' | sort)
[ "${#SRC_FILES[@]}" -gt 0 ] || die "没有 Java 源码"
"$JAVAC" -encoding UTF-8 --release 8 -Xlint:-options -classpath "$ANDROID_JAR" \
  -d "$CLASSES_DIR" "$R_JAVA" "${SRC_FILES[@]}" || die "javac 失败"
ok "$(find "$CLASSES_DIR" -name '*.class' | wc -l) 个 .class"

log "步骤 4/8  d8"
# 不用 d8.bat（它会用 PATH 里的 Java 8）
( cd "$BUILD_DIR" && "$JAVA" -Xmx1024M -cp "$D8_JAR" com.android.tools.r8.D8 \
    --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --release --output dex \
    $(find classes -name '*.class' | sort) ) || die "d8 失败"
[ -f "$DEX_DIR/classes.dex" ] || die "未产出 classes.dex"
ok "classes.dex $(stat -c %s "$DEX_DIR/classes.dex") 字节"

log "步骤 5/8  注入 classes.dex"
"$JAR" ufM "$UNSIGNED_APK" -C "$DEX_DIR" classes.dex || die "jar 注入失败"
ok "已注入"

log "步骤 6/8  修正 assets 条目名（Windows aapt2 反斜杠坑）"
python - "$UNSIGNED_APK" <<'PYEOF' || die "assets 条目名修正失败"
import struct, sys
BS = chr(92).encode()
SL = chr(47).encode()
p = sys.argv[1]
d = bytearray(open(p, 'rb').read())
i = d.rfind(b'PK' + bytes([5, 6]))
if i < 0:
    sys.exit('no EOCD')
n = struct.unpack_from('<H', d, i + 10)[0]
off = struct.unpack_from('<I', d, i + 16)[0]
fixed = 0
for _ in range(n):
    if bytes(d[off:off + 4]) != b'PK' + bytes([1, 2]):
        sys.exit('bad CEN entry')
    nlen, elen, clen = struct.unpack_from('<HHH', d, off + 28)
    lho = struct.unpack_from('<I', d, off + 42)[0]
    name = bytes(d[off + 46:off + 46 + nlen])
    if BS in name:
        new = name.replace(BS, SL)
        d[off + 46:off + 46 + nlen] = new
        if bytes(d[lho:lho + 4]) != b'PK' + bytes([3, 4]):
            sys.exit('bad LFH')
        lnlen = struct.unpack_from('<H', d, lho + 26)[0]
        if lnlen != nlen:
            sys.exit('name length mismatch')
        d[lho + 30:lho + 30 + lnlen] = new
        fixed += 1
    off += 46 + nlen + elen + clen
open(p, 'wb').write(d)
print('  renamed entries: %d' % fixed)
PYEOF
ok "assets 条目名已修正"

log "步骤 7/8  zipalign -p 4"
rm -f "$ALIGNED_APK"
"$ZIPALIGN" -p -f 4 "$UNSIGNED_APK" "$ALIGNED_APK" || die "zipalign 失败"
"$ZIPALIGN" -c -p 4 "$ALIGNED_APK" >/dev/null && ok "对齐校验通过"

log "步骤 8/8  签名"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  if [ ! -f "$KEY_PASS_FILE" ]; then
    PASS="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)"
    printf 'keystore: %s\nalias: %s\nstorepass: %s\nkeypass: %s\n\n【务必自行备份本文件与 keystore/ 目录】\n丢失后无法对已安装的版本做覆盖升级。\n' \
      "$KEYSTORE" "$KEY_ALIAS" "$PASS" "$PASS" > "$KEY_PASS_FILE"
    chmod 600 "$KEY_PASS_FILE" 2>/dev/null || true
  fi
  PASS="$(grep '^storepass:' "$KEY_PASS_FILE" | head -1 | awk '{print $2}')"
  "$KEYTOOL" -J-Duser.language=en -J-Duser.country=US -genkeypair \
    -keystore "$KEYSTORE" -storepass "$PASS" -keypass "$PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Lanyue Reader, OU=Personal, O=DSH, L=CN, C=CN" >/dev/null || die "keytool 失败"
  ok "已生成 release 密钥（口令见 keystore.local.txt）"
else
  ok "复用已有密钥 $KEYSTORE"
fi
PASS="$(grep '^storepass:' "$KEY_PASS_FILE" | head -1 | awk '{print $2}')"
rm -f "$FINAL_APK"
"$JAVA" -jar "$APKSIGNER_JAR" sign --ks "$KEYSTORE" \
  --ks-pass "pass:$PASS" --key-pass "pass:$PASS" --ks-key-alias "$KEY_ALIAS" \
  --min-sdk-version "$MIN_SDK" --out "$FINAL_APK" "$ALIGNED_APK" || die "apksigner 失败"
"$ZIPALIGN" -c -p 4 "$FINAL_APK" >/dev/null 2>&1 && ok "签名后对齐仍成立" || die "签名破坏了对齐"

audit "$FINAL_APK"
printf '\n\033[32m构建成功：%s\033[0m\n' "$FINAL_APK"
echo "安装：adb install -r \"$FINAL_APK\""
