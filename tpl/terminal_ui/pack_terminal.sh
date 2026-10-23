#!/bin/bash
# 打包终端风格模板

cd "$(dirname "$0")"

# 创建 assets 目录
mkdir -p assets

# 复制背景图
cp bg.png assets/

# 打包为 MAML zip 格式
zip -r terminal_ui.zip manifest.xml assets/

echo "打包完成: terminal_ui.zip"
ls -lh terminal_ui.zip