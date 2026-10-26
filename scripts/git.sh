#!/bin/bash
# 仓库包装器：git 目录放在 sdcard 上，避免 Operit 工作区文件层（l2s）
# 把 git 对象写成符号链接链，导致 git 报 "bad object HEAD"。
#
# 用法（与普通 git 完全一致）：
#   bash scripts/git.sh status
#   bash scripts/git.sh log --oneline -5
#   bash scripts/git.sh add -A && bash scripts/git.sh commit -m "msg"
#   bash scripts/git.sh checkout -- app/src/...
ROOT=$(cd "$(dirname "$0")/.." && pwd)
GITDIR="${RP_GITDIR:-/sdcard/Android/code/rearprogress_git}"

if [ ! -d "$GITDIR" ]; then
    echo "git dir not found: $GITDIR" >&2
    exit 1
fi

exec git --git-dir="$GITDIR" --work-tree="$ROOT" "$@"