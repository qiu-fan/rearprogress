#!/system/bin/sh
# rear-progress —— 向背屏进度卡推送进度
#
# 用法：
#   rear-progress "3/8 正在查接口"     # 更新进度
#   rear-progress                      # 查看当前进度
#   rear-progress --clear              # 清除进度
#
# 原理：只写 /data/local/tmp/agent_progress.txt。
# 模块 RearProgress 在副屏中心进程内每 1.2 秒轮询该文件，
# 内容变化则原地改写进度卡文字（不增删卡片、id 不变）。
#
# 前提：背屏上需先存在一张以 [AGENT] 开头的便签（模块只维护这一张）。

PROGRESS_FILE=/data/local/tmp/agent_progress.txt
TARGET_PKG=com.xiaomi.subscreencenter

case "$1" in
  "")
    echo "当前进度："
    cat "$PROGRESS_FILE" 2>/dev/null || echo "(未设置)"
    exit 0
    ;;
  --clear)
    rm -f "$PROGRESS_FILE"
    echo "已清除进度（背屏卡片保留最后内容）"
    exit 0
    ;;
esac

TEXT="$*"

# 原子写入，避免模块读到半截内容
TMP="${PROGRESS_FILE}.tmp.$$"
printf '%s' "$TEXT" > "$TMP" || exit 1
chmod 644 "$TMP"
mv -f "$TMP" "$PROGRESS_FILE"

echo "已推送：$TEXT"

# 首次使用时，若背屏还没有进度卡，则创建一张
if ! cmd app_function execute-app-function --package "$TARGET_PKG" \
      --function 'com.xiaomi.subscreencenter.appfunction.StickyNoteFunctions#getRearStickyNotes' \
      --parameters '{}' 2>/dev/null | grep -q 'AGENT'; then
  echo "背屏暂无进度卡，正在创建..."
  cmd app_function execute-app-function --package "$TARGET_PKG" \
    --function 'com.xiaomi.subscreencenter.appfunction.StickyNoteFunctions#addTextStickyNote' \
    --parameters "{\"text\":\"[AGENT] $TEXT\"}" >/dev/null 2>&1
  echo "已创建进度卡（这是唯一一次会带动画）"
fi