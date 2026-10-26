#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
星回 · token 字体改造
基于 DroidSansMono.ttf，做两件事：
1. K/M/B 的 advance 缩窄（数字保持等宽不变）
2. K/M/B 的字形横向压缩（保持视觉协调）

输出：rp_token.ttf
"""
from fontTools.ttLib import TTFont
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.misc.transform import Transform

SRC = "/sdcard/Android/code/DroidSansMono.ttf"
DST = "/sdcard/Android/code/rp_token.ttf"

# K/M/B 目标宽度（相对原 advance 的比例）
NARROW_RATIO = 0.62   # K/M/B 缩到 62%

f = TTFont(SRC)
upm = f['head'].unitsPerEm
cmap = f.getBestCmap()
glyf = f['glyf']
hmtx = f['hmtx']

orig_adv = hmtx['K'][0]
new_adv = int(orig_adv * NARROW_RATIO)
scale_x = new_adv / orig_adv

print(f"UPM={upm}  orig_adv={orig_adv}  new_adv={new_adv}  scale_x={scale_x:.3f}")

# 需要处理的字形
targets = {}
for ch in 'KMB':
    cp = ord(ch)
    if cp in cmap:
        gname = cmap[cp]
        targets[gname] = ch
        print(f"  {ch} -> glyph {gname}")

for gname, ch in targets.items():
    g = glyf[gname]
    if g.numberOfContours == 0:
        continue

    # 1. 横向压缩字形坐标
    for i in range(len(g.coordinates)):
        x, y = g.coordinates[i]
        g.coordinates[i] = (int(x * scale_x), y)

    # 更新字形边界
    g.recalcBounds(glyf)

    # 2. 更新 advance width
    adv, lsb = hmtx[gname]
    hmtx[gname] = (new_adv, g.xMin)
    print(f"  {ch}: advance {adv} -> {new_adv}, bbox x=[{g.xMin},{g.xMax}]")

# 3. 重新生成 hmtx 的 side bearings（确保一致性）
for gname, ch in targets.items():
    g = glyf[gname]
    hmtx[gname] = (new_adv, g.xMin)

f.save(DST)
print(f"\nSaved: {DST}")

# 验证
f2 = TTFont(DST)
cmap2 = f2.getBestCmap()
hmtx2 = f2['hmtx']
for ch in '0123456789KMB':
    cp = ord(ch)
    if cp in cmap2:
        gname = cmap2[cp]
        adv = hmtx2[gname][0]
        print(f"  {ch}: advance={adv}")