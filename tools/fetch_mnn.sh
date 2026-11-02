#!/usr/bin/env bash
# 取 MNN 的鸿蒙预编译包（头文件 + libMNN.so，arm64）。
# 不入库，由 CI 缓存；本机要用时手动跑一次。
set -euo pipefail

version="${MNN_VERSION:-3.6.2}"
dest="${1:-$(git rev-parse --show-toplevel)/Harmony/main/src/main/cpp/third_party/mnn}"
url="https://github.com/alibaba/MNN/releases/download/${version}/mnn_${version}_harmony_arm64_cpu.zip"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# DevEco 容器里没有 curl，按 wget → node 退而求其次
fetch() {
    if command -v curl > /dev/null; then
        curl -fsSL -o "$2" "$1"
    elif command -v wget > /dev/null; then
        wget -q -O "$2" "$1"
    else
        node -e '
          const fs = require("fs");
          fetch(process.argv[1]).then(async (r) => {
            if (!r.ok) process.exit(1);
            fs.writeFileSync(process.argv[2], Buffer.from(await r.arrayBuffer()));
          }).catch(() => process.exit(1));
        ' "$1" "$2"
    fi
}

echo "[mnn] $url"
fetch "$url" "$tmp/mnn.zip"

rm -rf "$dest"
mkdir -p "$dest"
unzip -q "$tmp/mnn.zip" -d "$dest"

# 官方预编译包只有 lib/，没有 include/（头文件已 vendored 在 Native/adapters/mnn/include）
lib=$(find "$dest" -name 'libMNN.so' | head -1) || true
[ -n "${lib:-}" ] || { echo "[mnn] 包里没有 libMNN.so"; find "$dest" -maxdepth 3 | head -20; exit 1; }
echo "[mnn] ok: $(du -sh "$dest" | cut -f1)  $lib"
