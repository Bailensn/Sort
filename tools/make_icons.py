"""生成三端共用的应用图标（几何与 Android 的 ic_launcher_foreground.xml 一致）。

没有 Pillow，所以自己栅格化 + 手写 PNG 编码；改动图形时只改这里的常量。
用法：python3 tools/make_icons.py
"""

import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BG = (0x0B, 0x6B, 0x5F)
FG = (0xFF, 0xFF, 0xFF)

# 设计稿以 Android 的 108 单位为画布
SHEET = (28.0, 28.0, 74.0, 62.0, 4.0)
STROKE = 2.4
ROWS = (40.0, 51.0)
COL = 47.0
ARROW = ((62.0, 70.0), (62.0, 84.0))
CHEVRON = ((56.0, 78.0), (62.0, 84.0), (68.0, 78.0))
STROKE_ARROW = 3.0
GLYPH_CENTER = (51.0, 56.0)
GLYPH_HEIGHT = 58.0


def _round_rect_sdf(px, py, x0, y0, x1, y1, r):
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    hw, hh = (x1 - x0) / 2 - r, (y1 - y0) / 2 - r
    dx, dy = abs(px - cx) - hw, abs(py - cy) - hh
    ax, ay = max(dx, 0.0), max(dy, 0.0)
    return math.hypot(ax, ay) + min(max(dx, dy), 0.0) - r


def _segment_sdf(px, py, a, b):
    ax, ay = a
    bx, by = b
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    length2 = vx * vx + vy * vy
    t = 0.0 if length2 == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / length2))
    return math.hypot(wx - vx * t, wy - vy * t)


def _rect(x0, y0, x1, y1, r):
    return lambda px, py: _round_rect_sdf(px, py, x0, y0, x1, y1, r)


def _seg(a, b):
    return lambda px, py: _segment_sdf(px, py, a, b)


def _shapes():
    """(sdf 函数, 半线宽, 包围盒) 列表。"""
    x0, y0, x1, y1, r = SHEET
    out = [(_rect(x0, y0, x1, y1, r), STROKE / 2, (x0, y0, x1, y1))]
    for y in ROWS:
        out.append((_seg((x0, y), (x1, y)), STROKE / 2, (x0, y, x1, y)))
    out.append((_seg((COL, y0), (COL, y1)), STROKE / 2, (COL, y0, COL, y1)))
    out.append((_seg(ARROW[0], ARROW[1]), STROKE_ARROW / 2, (ARROW[0][0], ARROW[0][1], ARROW[1][0], ARROW[1][1])))
    for a, b in zip(CHEVRON, CHEVRON[1:]):
        out.append((_seg(a, b), STROKE_ARROW / 2, (a[0], a[1], b[0], b[1])))
    return out


def coverage(size, scale):
    """返回 size*size 的 0..1 覆盖度（灰度），只算每个形状的包围盒。"""
    cov = [0.0] * (size * size)
    cx, cy = size / 2, size / 2
    # 设计坐标 → 像素
    def to_px(x, y):
        return (cx + (x - GLYPH_CENTER[0]) * scale, cy + (y - GLYPH_CENTER[1]) * scale)

    for sdf, half, (bx0, by0, bx1, by1) in _shapes():
        px0, py0 = to_px(min(bx0, bx1) - half, min(by0, by1) - half)
        px1, py1 = to_px(max(bx0, bx1) + half, max(by0, by1) + half)
        left, top = max(0, int(math.floor(px0))), max(0, int(math.floor(py0)))
        right, bottom = min(size, int(math.ceil(px1)) + 1), min(size, int(math.ceil(py1)) + 1)

        for py in range(top, bottom):
            for px in range(left, right):
                hit = 0
                for sy in (0.25, 0.75):
                    for sx in (0.25, 0.75):
                        ax = (px + sx - cx) / scale + GLYPH_CENTER[0]
                        ay = (py + sy - cy) / scale + GLYPH_CENTER[1]
                        if abs(sdf(ax, ay)) <= half:
                            hit += 1
                if hit:
                    idx = py * size + px
                    cov[idx] = min(1.0, cov[idx] + hit / 4)

    return cov


def render(size, scale, background):
    cov = coverage(size, scale)
    rows = []
    for y in range(size):
        row = bytearray()
        for x in range(size):
            a = cov[y * size + x]
            if background is None:
                color = FG + (int(a * 255),)
            else:
                color = tuple(
                    int(round(background[i] * (1 - a) + FG[i] * a)) for i in range(3)
                )
            row += bytes(color)
        rows.append(bytes(row))
    return rows


def write_png(path, size, rows, alpha):
    raw = b"".join(b"\x00" + r for r in rows)

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    # iOS 的 AppIcon 带 alpha 会被 App Store 拒，所以不透明的图用 RGB
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6 if alpha else 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)
    print(f"{path.relative_to(ROOT)}  {size}x{size}  {len(png) // 1024} KB")


def main():
    ios = ROOT / "iOS/ios-app/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
    harmony = ROOT / "Harmony/main/src/main/resources/base/media"
    scope = ROOT / "Harmony/AppScope/resources/base/media"

    # iOS 要求不透明、不要自带的圆角
    write_png(ios, 1024, render(1024, 1024 * 0.62 / GLYPH_HEIGHT, BG), False)

    glyph_scale = 1024 * 0.52 / GLYPH_HEIGHT
    fg = render(1024, glyph_scale, None)
    bg = [bytes(BG) * 1024] * 1024
    write_png(harmony / "foreground.png", 1024, fg, True)
    write_png(harmony / "background.png", 1024, bg, False)
    write_png(scope / "foreground.png", 1024, fg, True)
    write_png(scope / "background.png", 1024, bg, False)

    # 启动图标：整块底色 + 居中字形
    write_png(harmony / "startIcon.png", 512, render(512, 512 * 0.62 / GLYPH_HEIGHT, BG), False)


if __name__ == "__main__":
    main()
