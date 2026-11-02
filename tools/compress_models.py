"""把 iOS 打包用的模型压成 zlib，并删掉原始文件。

格式：4 字节小端原始长度 + zlib 流，和 Swift 侧 ModelLoader 对应。
"""

import pathlib
import struct
import sys
import zlib


def main(paths):
    for name in paths:
        raw = pathlib.Path(name)
        data = raw.read_bytes()
        packed = pathlib.Path(f"{name}.zlib")
        packed.write_bytes(struct.pack("<I", len(data)) + zlib.compress(data, 6))
        raw.unlink()
        print(f"{name} -> {packed.name} ({len(data)} -> {packed.stat().st_size})")


if __name__ == "__main__":
    main(sys.argv[1:])
