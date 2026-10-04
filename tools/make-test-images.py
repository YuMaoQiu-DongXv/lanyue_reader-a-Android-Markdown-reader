#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成测试文档用的图片（纯标准库手写 PNG，不依赖 PIL）。

刻意造出几种"路径形态"来验证相对图片解析：
  img-01-blue.png          普通英文名
  img 02 with space.png    文件名含空格（markdown 里必须能编码成 %20）
  中文图片名-03.png        文件名含中文
另有一张故意不生成，用来验证"图片缺失"的兜底块。
"""
import os
import struct
import zlib

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'testdoc', 'test-images')


def write_png(path, w, h, fn):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw += bytes(fn(x, y, w, h))
    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff))
    ihdr = struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)
    blob = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr)
            + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b''))
    with open(path, 'wb') as f:
        f.write(blob)
    return len(blob)


def make(path, base, marks, size=(820, 300)):
    """base=底色, marks=要画的方块数（用来肉眼区分是哪张图）"""
    w, h = size

    def px(x, y, w, h):
        # 边框
        if x < 6 or y < 6 or x >= w - 6 or y >= h - 6:
            return (255, 255, 255)
        # 对角斜纹（便于判断是否被拉伸变形）
        if (x + y) % 46 < 23:
            r, g, b = base
            k = 0.86
            return (int(r * k), int(g * k), int(b * k))
        return base

    def marked(x, y, w, h):
        r, g, b = px(x, y, w, h)
        # 在中间画 marks 个白底蓝框方块（用数量区分图片）
        bx0, by0, size, gap = 60, 105, 74, 18
        for i in range(marks):
            x0 = bx0 + i * (size + gap)
            if x0 <= x <= x0 + size and by0 <= y <= by0 + size:
                if x0 + 6 <= x <= x0 + size - 6 and by0 + 6 <= y <= by0 + size - 6:
                    return (255, 255, 255)
                return (26, 35, 58)
        return (r, g, b)

    return write_png(path, w, h, marked)


def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    total += make(os.path.join(OUT, 'img-01-blue.png'), (22, 112, 232), 1)
    total += make(os.path.join(OUT, 'img 02 with space.png'), (15, 138, 99), 2)
    total += make(os.path.join(OUT, '中文图片名-03.png'), (201, 105, 30), 3)
    # 2 倍尺寸版本：专门给"相机查看器二级加载"的测试做对照（1640x600）
    total += make(os.path.join(OUT, 'img-04-big.png'), (22, 112, 232), 4, (1640, 600))
    print('生成图片到', os.path.normpath(OUT))
    for n in sorted(os.listdir(OUT)):
        print('  %-28s %d 字节' % (n, os.path.getsize(os.path.join(OUT, n))))
    print('合计 %d 字节' % total)


if __name__ == '__main__':
    main()
