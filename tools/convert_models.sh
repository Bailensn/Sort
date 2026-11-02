#!/usr/bin/env bash
# 把 PP-OCR 的 ONNX 模型转成 MindSpore Lite 的 .ms，产物：det.ms / rec.ms / rec.yml。
# 用法：convert_models.sh <输出目录>
set -euo pipefail

out="${1:-models}"
version="${MSLITE_VERSION:-2.10.0}"
hf="${HF:-https://huggingface.co/PaddlePaddle}"

mkdir -p "$out"

# 鸿蒙的 DevEco 容器里没有 curl，按 curl → wget → node 的顺序退而求其次。
fetch() {
    if command -v curl > /dev/null; then
        curl -fsSL -o "$2" "$1"
    elif command -v wget > /dev/null; then
        wget -q -O "$2" "$1"
    else
        node -e '
          const fs = require("fs");
          const [url, out] = process.argv.slice(1);
          fetch(url).then(async (r) => {
            if (!r.ok) process.exit(1);
            fs.writeFileSync(out, Buffer.from(await r.arrayBuffer()));
          }).catch(() => process.exit(1));
        ' "$1" "$2"
    fi
}

echo "[models] 下载 ONNX 模型"
fetch "$hf/PP-OCRv6_medium_det_onnx/resolve/main/inference.onnx" "$out/det.onnx"
fetch "$hf/PP-OCRv6_medium_rec_onnx/resolve/main/inference.onnx" "$out/rec.onnx"
fetch "$hf/PP-OCRv6_medium_rec_onnx/resolve/main/inference.yml" "$out/rec.yml"

echo "[models] 下载 MindSpore Lite $version"
toolkit="$out/mslite.tar.gz"
base="https://ms-release.obs.cn-north-4.myhuaweicloud.com/${version}/MindSporeLite/lite/release/linux/x86_64"
fetch "$base/mindspore-lite-${version}-linux-x64.tar.gz" "$toolkit"
tar xzf "$toolkit" -C "$out"

converter="$(find "$out"/mindspore-lite-* -name converter_lite | head -1)"
[ -n "$converter" ] || { echo "[models] 找不到 converter_lite"; exit 1; }
libdirs="$(find "$out"/mindspore-lite-* -name '*.so*' -printf '%h\n' | sort -u | tr '\n' ':')"
export LD_LIBRARY_PATH="${libdirs}${LD_LIBRARY_PATH:-}"

echo "[models] 转换（不指定 inputShape，保留动态维度）"
"$converter" --fmk=ONNX --modelFile="$out/det.onnx" --outputFile="$out/det"
"$converter" --fmk=ONNX --modelFile="$out/rec.onnx" --outputFile="$out/rec"

# 主机验证要复用 runtime/ 里的 libmindspore-lite.so 和头文件
if [ "${MSLITE_KEEP_RUNTIME:-0}" = "1" ]; then
    echo "[models] 保留运行时：$(ls -d "$out"/mindspore-lite-*)"
    rm -f "$toolkit" "$out"/*.onnx
else
    rm -rf "$out"/mindspore-lite-* "$toolkit" "$out"/*.onnx
fi
ls -lh "$out"
