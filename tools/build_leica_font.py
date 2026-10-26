#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
星回 · 徕卡字体改造
基于 LG1056-Regular.otf（徕卡数字时钟字体），做两件事：
1. 数字 0-9 统一 advance = 573（等宽）
2. K/M/B 横向缩放 + advance 缩窄

输出：rp_leica_mono.otf
"""
from fontTools.ttLib import TTFont
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.misc.transform import Transform

SRC = "/tmp/leica/etc/LG1056-Regular.otf"
DST = "/sdcard/Android/code/rp_leica_mono.otf"

DIGIT_ADV = 573      # 数字统一宽度
KMB_RATIO = 0.68     # K/M/B 缩到原宽度的 68%

f = TTFont(SRC)
upm = f['head'].unitsPerEm
cmap = f.getBestCmap()
hmtx = f['hmtx']
glyphset = f.getGlyphSet()

print(f"UPM={upm}")

# === 1. 数字等宽 ===
print("\n--- 数字等宽 ---")
for ch in '0123456789':
    cp = ord(ch)
    if cp in cmap:
        gname = cmap[cp]
        old_adv = hmtx[gname][0]
        hmtx[gname] = (DIGIT_ADV, hmtx[gname][1])
        print(f"  {ch}: {old_adv} -> {DIGIT_ADV}")

# === 2. K/M/B 缩窄 ===
print("\n--- K/M/B 缩窄 ---")
for ch in 'KMB':
    cp = ord(ch)
    if cp not in cmap:
        continue
    gname = cmap[cp]
    old_adv, old_lsb = hmtx[gname]

    # 获取字形边界
    bp = BoundsPen(glyphset)
    glyphset[gname].draw(bp)
    if not bp.bounds:
        continue
    xmin, ymin, xmax, ymax = bp.bounds
    glyph_w = xmax - xmin

    # 计算缩放比例
    new_glyph_w = int(glyph_w * KMB_RATIO)
    scale_x = new_glyph_w / glyph_w
    new_adv = int(old_adv * KMB_RATIO)

    print(f"  {ch}: adv {old_adv} -> {new_adv}, glyph_w {glyph_w} -> {new_glyph_w}, scale={scale_x:.3f}")

    # CFF 字体：用 TransformPen 重新绘制字形
    from fontTools.pens.t2CharStringPen import T2CharStringPen
    from fontTools.pens.recordingPen import DecomposingRecordingPen

    # 记录原始路径
    rec = DecomposingRecordingPen(glyphset)
    glyphset[gname].draw(rec)

    # 创建新的 T2 pen，应用水平缩放
    new_width = new_adv
    t2pen = T2CharStringPen(new_width, glyphset)
    rec.replay(TransformPen(t2pen, Transform(scale_x, 0, 0, 1, 0, 0)))

    # 替换字形（CFF 正确访问方式）
    cff = f['CFF '].cff
    td = cff[cff.fontNames[0]]
    charStrings = td.CharStrings
    charStrings[gname] = t2pen.getCharString(private=td.Private)

    # 更新 advance
    hmtx[gname] = (new_adv, int(xmin * scale_x))

f.save(DST)
print(f"\nSaved: {DST}")

# === 验证 ===
print("\n--- 验证 ---")
f2 = TTFont(DST)
cmap2 = f2.getBestCmap()
hmtx2 = f2['hmtx']
for ch in '0123456789KMB':
    cp = ord(ch)
    if cp in cmap2:
        gname = cmap2[cp]
        adv = hmtx2[gname][0]
        print(f"  {ch}: advance={adv}")