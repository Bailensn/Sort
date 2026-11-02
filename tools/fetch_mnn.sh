#!/usr/bin/env bash
# 取 MNN 的鸿蒙预编译包（头文件 + libMNN.so，arm64）。
# 不入库，由 CI 缓存；本机要用时手动跑一次。
set -euo pipefail

version="${MNN_VERSION:-3.6.2}"
dest="${1:-$(git rev-parse --show-toplevel)/Harmony/main/src/main/cpp/third_party/mnn}"
url="https://github.com/alibaba/MNN/releases/download/${version}/mnn_${version}_harmony_arm64_cpu.zip"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "[mnn] $url"
curl -fsSL -o "$tmp/mnn.zip" "$url"

rm -rf "$dest"
mkdir -p "$dest"
unzip -q "$tmp/mnn.zip" -d "$dest"

[ -d "$dest/include" ] || { echo "[mnn] 没解出 include/"; exit 1; }
echo "[mnn] ok: $(du -sh "$dest" | cut -f1)  $(find "$dest" -name '*.so' | head -3 | tr '\n' ' ')"
