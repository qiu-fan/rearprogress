#!/bin/bash
# 编译 SystemUI 外挂 hook dex（热更新用）
#
# 产物：hot/rp_hook.dex
# 使用方法（改完 hot/src 下的代码后）：
#   bash scripts/build_hot.sh
#   cp -f hot/rp_hook.dex /data/local/tmp/rp_hook.dex
#   kill -9 $(pidof com.android.systemui)      # 重启 SystemUI 即生效，不用重启设备
set -e

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK="$HOME/Android"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
D8="$SDK/build-tools/35.0.0/d8"
XPOSED_JAR="$ROOT/app/libs/libxposed-102.jar"
OUT=/tmp/hot_build

if [ ! -f "$ANDROID_JAR" ]; then echo "missing android.jar: $ANDROID_JAR"; exit 1; fi
if [ ! -f "$XPOSED_JAR" ]; then echo "missing libxposed jar: $XPOSED_JAR"; exit 1; fi

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

SRC=$(find "$ROOT/hot/src" -name '*.java')
if [ -z "$SRC" ]; then echo "no java source under hot/src"; exit 1; fi

javac -nowarn -source 17 -target 17 \
    -classpath "$ANDROID_JAR:$XPOSED_JAR" \
    -d "$OUT/classes" $SRC 2>&1 | grep -v 'bootstrap class path' || true

CLASSES=$(find "$OUT/classes" -name '*.class')
"$D8" --lib "$ANDROID_JAR" --min-api 30 --output "$OUT/dex" $CLASSES

cp -f "$OUT/dex/classes.dex" "$ROOT/hot/rp_hook.dex"
echo "built: $ROOT/hot/rp_hook.dex ($(stat -c%s "$ROOT/hot/rp_hook.dex") bytes)"
