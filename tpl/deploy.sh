#!/system/bin/sh
# 部署背屏自定义模板
#
# 背景：副屏中心每次启动都会从 APK 重新释放
#   /data/system/theme_magic/users/<uid>/subscreencenter/smart_assistant/<name>
# 所以直接改那个文件会被覆盖（实测 md5 会回滚）。
# 模块在 m2.d.i() 返回值上把路径重定向到 /data/local/tmp/rp_tpl/<name>，
# 因此模板必须放在这个目录，文件名与原模板一致（不带 rp_ 前缀）。
#
# 用法：
#   sh deploy_tpl.sh                     # 部署仓库内的 tpl/lup_ui.zip
#   sh deploy_tpl.sh /path/to/custom.zip # 部署指定模板

set -u

SRC="${1:-$(dirname "$0")/lup_ui.zip}"
DST_DIR=/data/local/tmp/rp_tpl
DST="$DST_DIR/live_update_progress"

if [ ! -f "$SRC" ]; then
    echo "模板不存在: $SRC" >&2
    exit 1
fi

mkdir -p "$DST_DIR"
chmod 0755 "$DST_DIR"
cp -f "$SRC" "$DST"
chmod 0644 "$DST"

echo "已部署: $DST"
ls -l "$DST"
echo
echo "下一步：重启副屏中心让新模板生效"
echo "  am force-stop com.xiaomi.subscreencenter"
