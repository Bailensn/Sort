# 把完整构建日志推到 ci-log-* 分支：谁都能直接 fetch 下来看，不用人肉复制。
set -uo pipefail

source_file="${1:-build.log}"
target="${2:-build.log}"
branch="${3:-ci-logs}"

if [ ! -f "$source_file" ]; then
    echo "没有日志可发：$source_file"
    exit 0
fi
if ! command -v git > /dev/null; then
    echo "容器里没有 git，跳过日志发布"
    exit 0
fi

work="$(mktemp -d)"
# 全文照发，不截断；只有特别大的日志才压一下（内容还是全的）。
size=$(wc -c < "$source_file" 2>/dev/null || echo 0)
if [ "$size" -gt 20971520 ]; then
    gzip -c "$source_file" > "$work/$target.gz"
    target="$target.gz"
else
    cp "$source_file" "$work/$target"
fi

cd "$work" || exit 0
git init -q
git -c user.name=ci -c user.email=ci@localhost add "$target"
git -c user.name=ci -c user.email=ci@localhost commit -qm "CI 日志：$target"
git push -f "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" HEAD:"$branch"
