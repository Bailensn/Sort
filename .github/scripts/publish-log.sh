#!/usr/bin/env bash
# 把构建日志的尾巴推到 ci-logs 分支：谁都能直接 fetch 下来看，不用人肉复制。
set -uo pipefail

source_file="${1:-build.log}"
target="${2:-build.log}"

if [ ! -f "$source_file" ]; then
    echo "没有日志可发：$source_file"
    exit 0
fi
if ! command -v git > /dev/null; then
    echo "容器里没有 git，跳过日志发布"
    exit 0
fi

work="$(mktemp -d)"
tail -c 40000 "$source_file" > "$work/$target" 2>/dev/null || cp "$source_file" "$work/$target"

cd "$work" || exit 0
git init -q
git -c user.name=ci -c user.email=ci@localhost add "$target"
git -c user.name=ci -c user.email=ci@localhost commit -qm "CI 日志：$target"
git push -f "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" HEAD:ci-logs
