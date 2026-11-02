"""把 iOS 打包用的模型压成 gzip，并删掉原始文件。

标准 gzip（mtime 置 0），MT 管理器、7-Zip、gunzip 都能直接打开。
"""

import gzip
import pathlib
import sys


def main(paths):
    for name in paths:
        raw = pathlib.Path(name)
        data = raw.read_bytes()
        packed = pathlib.Path(f"{name}.gz")
        packed.write_bytes(gzip.compress(data, 6, mtime=0))
        raw.unlink()
        print(f"{name} -> {packed.name} ({len(data)} -> {packed.stat().st_size})")


if __name__ == "__main__":
    main(sys.argv[1:])
