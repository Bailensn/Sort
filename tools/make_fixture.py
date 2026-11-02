"""生成 OCR 冒烟测试用的表格图（白底黑线 + 5x7 点阵数字），三端共用同一份。

不依赖任何字体，输出可复现：iOS 用 PNG，C++ 的 ocr_dump / 主机测试用 PPM。
用法：python3 tools/make_fixture.py
"""

import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

W, H = 1300, 840
MARGIN = 60
COLS = [60, 260, 740, 1000, 1240]
ROWS = [60, 180, 300, 420, 540, 660, 780]
LINE = 3
SCALE = 6

# 5x7 点阵，只要 0-9
FONT = {
    "0": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11111", "00010", "00100", "00010", "00001", "10001", "01110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
}

ROWS_TEXT = [
    ["", "", "", ""],
    ["1", "900000000001", "95", "88"],
    ["2", "900000000002", "78", "92"],
    ["3", "900000000003", "66", "70"],
    ["4", "900000000004", "88", "100"],
    ["5", "900000000005", "72", "0"],
]


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = bytearray(b"\xff" * (w * h * 3))

    def fill(self, x, y, w, h, color):
        for row in range(max(0, y), min(self.h, y + h)):
            start = (row * self.w + max(0, x)) * 3
            self.px[start:start + len(range(max(0, x), min(self.w, x + w))) * 3] = (
                bytes(color) * len(range(max(0, x), min(self.w, x + w)))
            )

    def text(self, x, y, s, color):
        for index, ch in enumerate(s):
            glyph = FONT.get(ch)
            if glyph is None:
                continue
            for gy, line in enumerate(glyph):
                for gx, bit in enumerate(line):
                    if bit == "1":
                        self.fill(x + (index * 6 + gx) * SCALE, y + gy * SCALE, SCALE, SCALE, color)


def draw():
    canvas = Canvas(W, H)
    for x in COLS:
        canvas.fill(x, ROWS[0], LINE, ROWS[-1] - ROWS[0] + LINE, (0, 0, 0))
    for y in ROWS:
        canvas.fill(MARGIN, y, COLS[-1] - MARGIN, LINE, (0, 0, 0))

    for row, texts in enumerate(ROWS_TEXT):
        top, bottom = ROWS[row], ROWS[row + 1]
        for col, text in enumerate(texts):
            if not text:
                continue
            width = len(text) * 6 * SCALE - SCALE
            height = 7 * SCALE
            x = COLS[col] + (COLS[col + 1] - COLS[col] - width) // 2
            y = top + (bottom - top - height) // 2
            canvas.text(x, y, text, (0, 0, 0))
    return canvas


def write_png(path, canvas):
    raw = b"".join(
        b"\x00" + bytes(canvas.px[y * canvas.w * 3:(y + 1) * canvas.w * 3])
        for y in range(canvas.h)
    )

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", canvas.w, canvas.h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)
    print(f"{path.relative_to(ROOT)}  {canvas.w}x{canvas.h}  {len(png) // 1024} KB")


def write_ppm(path, canvas):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"P6\n%d %d\n255\n" % (canvas.w, canvas.h) + bytes(canvas.px))
    print(f"{path.relative_to(ROOT)}  {canvas.w}x{canvas.h}  {len(canvas.px) // 1024} KB")


if __name__ == "__main__":
    shared = draw()
    write_png(ROOT / "iOS/ios-app/Tests/Fixtures/table.png", shared)
    write_ppm(ROOT / "Native/tests/data/table.ppm", shared)
