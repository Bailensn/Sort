#!/usr/bin/env bash
# 取 iOS 用的 ONNX Runtime xcframework：官方 pod 包就是一个 zip，不用 CocoaPods。
# 不入库（62 MB），CI 用 actions/cache 兜住。
set -euo pipefail

version="${ORT_VERSION:-1.30.0}"
dest="${1:-$(git rev-parse --show-toplevel)/iOS/ios-app/Vendor}"
url="https://download.onnxruntime.ai/pod-archive-onnxruntime-c-${version}.zip"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "[ort] $url"
curl -fsSL -o "$tmp/ort.zip" "$url"

rm -rf "$dest"
mkdir -p "$dest"
unzip -q "$tmp/ort.zip" -d "$dest"

[ -d "$dest/onnxruntime.xcframework" ] || { echo "[ort] 没解出 xcframework"; exit 1; }
[ -f "$dest/Headers/onnxruntime_cxx_api.h" ] || { echo "[ort] 没解出头文件"; exit 1; }
echo "[ort] ok: $(du -sh "$dest" | cut -f1)"
