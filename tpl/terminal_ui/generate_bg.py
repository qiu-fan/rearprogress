#!/usr/bin/env python3
"""
终端风格背景图生成器
生成一个深色终端背景，带网格纹理
"""
from PIL import Image, ImageDraw

# 创建 976x596 的深色背景
img = Image.new('RGBA', (976, 596), (10, 10, 10, 255))
draw = ImageDraw.Draw(img)

# 绘制网格纹理（模拟终端）
grid_color = (0, 30, 0, 40)  # 深绿色半透明
for x in range(0, 976, 20):
    draw.line([(x, 0), (x, 596)], fill=grid_color, width=1)
for y in range(0, 596, 20):
    draw.line([(0, y), (976, y)], fill=grid_color, width=1)

# 添加扫描线效果（可选）
for y in range(0, 596, 4):
    draw.line([(0, y), (976, y)], fill=(0, 0, 0, 20), width=1)

# 保存
img.save('bg.png', 'PNG')
print("终端背景图生成完成: bg.png")