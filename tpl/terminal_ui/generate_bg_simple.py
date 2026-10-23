#!/usr/bin/env python3
"""
终端风格背景图生成器 (无PIL版本)
生成一个深色终端背景，带网格纹理
"""
import struct
import zlib

# 创建 976x596 的深色背景
width, height = 976, 596
bg_color = (10, 10, 10, 255)  # 深色背景
grid_color = (0, 20, 0, 255)  # 深绿色网格线

# 创建像素数据
pixels = bytearray()
for y in range(height):
    for x in range(width):
        # 绘制网格
        if x % 20 == 0 or y % 20 == 0:
            pixels.extend(grid_color)
        else:
            pixels.extend(bg_color)

# PNG 编码
def create_png(width, height, pixels):
    def chunk(chunk_type, data):
        c = chunk_type + data
        crc = struct.pack('>I', zlib.crc32(c) & 0xffffffff)
        return struct.pack('>I', len(data)) + c + crc

    # PNG 文件头
    signature = b'\x89PNG\r\n\x1a\n'
    
    # IHDR
    ihdr_data = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    ihdr = chunk(b'IHDR', ihdr_data)
    
    # IDAT
    raw_data = bytearray()
    for y in range(height):
        raw_data.append(0)  # filter type
        start = y * width * 4
        raw_data.extend(pixels[start:start + width * 4])
    
    compressed = zlib.compress(bytes(raw_data))
    idat = chunk(b'IDAT', compressed)
    
    # IEND
    iend = chunk(b'IEND', b'')
    
    return signature + ihdr + idat + iend

# 生成 PNG
png_data = create_png(width, height, pixels)

with open('bg.png', 'wb') as f:
    f.write(png_data)

print(f"终端背景图生成完成: bg.png ({len(png_data)} 字节)")