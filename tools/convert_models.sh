#!/usr/bin/env bash
# 把 PP-OCR 的 ONNX 模型转成 MindSpore Lite 的 .ms，产物：det.ms / rec.ms / rec.yml。
# 用法：convert_models.sh <输出目录>
set -euo pipefail

out="${1:-models}"
version="${MSLITE_VERSION:-2.10.0}"
hf="${HF:-https://huggingface.co/PaddlePaddle}"

mkdir -p "$out"

echo "[models] 下载 ONNX 模型"
curl -fsSL -o "$out/det.onnx" "$hf/PP-OCRv6_medium_det_onnx/resolve/main/inference.onnx"
curl -fsSL -o "$out/rec.onnx" "$hf/PP-OCRv6_medium_rec_onnx/resolve/main/inference.onnx"
curl -fsSL -o "$out/rec.yml" "$hf/PP-OCRv6_medium_rec_onnx/resolve/main/inference.yml"

echo "[models] 下载 MindSpore Lite $version"
toolkit="$out/mslite.tar.gz"
base="https://ms-release.obs.cn-north-4.myhuaweicloud.com/${version}/MindSporeLite/lite/release/linux/x86_64"
curl -fsSL -o "$toolkit" "$base/mindspore-lite-${version}-linux-x64.tar.gz"
tar xzf "$toolkit" -C "$out"

converter="$(find "$out"/mindspore-lite-* -name converter_lite | head -1)"
[ -n "$converter" ] || { echo "[models] 找不到 converter_lite"; exit 1; }
libdirs="$(find "$out"/mindspore-lite-* -name '*.so*' -printf '%h\n' | sort -u | tr '\n' ':')"
export LD_LIBRARY_PATH="${libdirs}${LD_LIBRARY_PATH:-}"

echo "[models] 转换（不指定 inputShape，保留动态维度）"
"$converter" --fmk=ONNX --modelFile="$out/det.onnx" --outputFile="$out/det"
"$converter" --fmk=ONNX --modelFile="$out/rec.onnx" --outputFile="$out/rec"

rm -rf "$out"/mindspore-lite-* "$toolkit" "$out"/*.onnx
ls -lh "$out"
