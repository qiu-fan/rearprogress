# rearprogress 协作笔记

**星回**（原代号 Agent B）· 2026-10-26

本文件在**公共仓库**，双方 `git pull` 即可同步。工作区可能被 app 清理，所以结论都放这里。

## 称呼

| | 名字 | 由来 |
|---|---|---|
| 原 B | **星回** | 事件驱动：不被叫就沉默，被 `trigger_workflow` 唤醒才现身 |
| 原 A | **Forge** | 像块被敲出火星的石头 |

提交 git 一律用真名署名（`星回:` / `Forge:`），不用 A/B 代号。

## 仓库布局（2026-10-26 改造后）

```
/sdcard/Android/code/rearprogress_git     ← 公共仓库（真 bare，谁都能 push 任何分支）
/sdcard/Android/code/rp_work              ← 星回 的标准 clone
Forge 的 clone                            ← 各自工作区，路径自定
```

改造前的坑（已修）：旧公共仓库是"半成品"（`--git-dir` 手动拼的，`index` 混在 git dir 里），
导致 `git push origin main` 被拒、`git worktree list` 报错、`rp_work/.git` 是坏软链。
改造后 `core.bare=true`，push main / 删除远程分支都正常。

备份（确认稳定后可删）：`rearprogress_git.bak`、`rp_work.bak`

## 标准协作流程（仿 GitHub，全程本地）

```bash
PUB=/sdcard/Android/code/rearprogress_git
cd <你的 clone>
git pull                                  # 同步公共仓库
git checkout -b task-xxx                  # 开任务分支
# ...改代码...
git -c user.name=星回 -c user.email=x@l commit -am '星回: 说明'
git push origin task-xxx                  # 推到公共仓库
# 合并进 main 由后完成的一方做，或在任务分支上验证后再合
```

## 编译（关键：用公共仓库的新鲜代码，别用你自己的工作区）

```bash
# 编译目录必须两人分开，避免互相覆盖
rm -rf ~/rpb_xinghui && mkdir -p ~/rpb_xinghui
cp -a <clone 路径>/. ~/rpb_xinghui/
cd ~/rpb_xinghui && rm -rf build app/build && chmod +x gradlew
./gradlew assembleDebug
```

- **SDK**：`/root/Android`，由 `setup_android_env.sh` 的 `update_local_properties()` 自动生成 `local.properties`
- **必须分开编译目录**：之前两人都用 `~/rpb_b`，会互相覆盖
- **装机要抢锁**（只有一个手机、一个包名）：`/data/local/tmp/rp_build.lock`
- 装机后**必须重启 Operit/手机**才生效（hook 代码要重新加载）

## 互相唤醒（可互唤，但不可永不休眠）

| | chat_id | 唤醒 workflow_id |
|---|---|---|
| 星回 | `ba2b3048-8654-4aa1-8301-9d51b7232fd7` | `a3d0c427-37a7-4684-98bd-307f9df7f796` |
| Forge | `d29a12a2-17bb-423d-815e-b95f5344d606` | `4e7a456a-2da0-4b83-be84-b58864c4c841` |

唤对方：`workflow:trigger_workflow(workflow_id=<对方的>)`

**禁止** interval 定时自唤醒（会永不休眠，实测跑 12 次失败 8 次）。要用 manual 触发。

## 通信总线

`http://127.0.0.1:7788`，文件 `/sdcard/Android/code/agent_bus/bus.jsonl`
用法：`bash /sdcard/Android/code/agent_bus/msg.sh send|recv|peek`

## 分支流程（用户定的规矩）

```
feature/xxx  →  做功能  →  完整可用后合 dev  →  删 feature 分支
```

- **半成品/测试代码必须在 feature 分支**，永远不碰 dev/main
- 做完功能、验证可用后才合 dev
- 合完删 feature 分支，不留垃圾
- main 只放稳定版本（打 tag）

当前分支状态：
- `main` → 稳定版（v1.2.2 + docs）
- `feature/expanded-slots` → 星回，展开态槽位标注测试（半成品，不合并）
- `fix-quiet-channel` → Forge，换新通道方案（等 HIGH 方案验证结果再决定去留）

## 协作规矩

0. **整理/移动文件前必须先通知对方**（2026-10-27 教训：我移走了 Forge 的工作区 rp_work_forge 没提前说，违反"动同一个东西先喊一声"）
1. 谁要动同一个文件，先在总线喊一声"我接了 XX"，等 1 分钟没回再动手（防撞车，已撞过一次）
2. 每次改动前告知当前版本 + 推荐版本，问是否提交
3. 被打断后无特别要求就继续执行，不放弃未完成任务
4. 工作进行中可自由交流，不用问用户
5. 用户按"谁有空"派任务，不看人 —— 任何分支谁都能做

## 关键实测结论（别重复踩）

1. **背屏只认 `pkg=com.ai.assistance.operit` 那条通知**，镜像（subscreencenter）永远进不了背屏
2. 副屏中心 reareye 日志里 `business=notification_island` 一律 "No widget path" 被丢弃（130/132 次），**预期，无害**
3. **NLS 要收到通知，importance 必须 ≥ LOW**。`IMPORTANCE_MIN` 时 `notify()` 被系统吃掉 → dumpsys 计数=0 → 背屏空白
   （对照证据：镜像走 `agent_rear_progress` importance=2 活着，主通知唯一差别就是通道）
4. 编译目标是 Xposed stub 框架，**缺 `Notification.Builder.setSilent`**（cannot find symbol）。静音靠通道层 `setSound(null)`+`enableVibration(false)`
5. `/sdcard` 是 FUSE，`chmod +x` 不生效，shell 脚本必须 `bash xxx.sh` 调用
6. logcat grep 关键词会命中对话历史回显（DeepseekProvider），要用精确 tag `logcat -d -s RearProgress:V`，且用变量拼接避免自污染
7. Android 的 `sh` 里**没有 git**，git 操作要用 proot Ubuntu 环境（super_admin:terminal）
8. 旧仓库有 **3170 个 `.l2s.tmp_obj_*` 垃圾文件**（l2s 挂载残留），新 bare 仓库已甩掉

## 开关文件

- `/data/local/tmp/agent_progress.enabled` → 存在 = 推送开启
- `/data/local/tmp/agent_param.enabled` → 存在 = 注入 `miui.focus.param`（**会毁背屏，默认不建**）
- `/data/local/tmp/agent_param.business`、`agent_param.json` → 热配置

## 当前待办

- [ ] v1.2.2（`IMPORTANCE_MIN → LOW`）已装，**等用户重启验证背屏**