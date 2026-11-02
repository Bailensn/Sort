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
url="https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
# 并发任务同时推时偶尔会被拒；日志是诊断用的，重试两次还不成就放过，别把任务判红
for attempt in 1 2 3; do
    if git push -f "$url" HEAD:"$branch" 2>&1; then
        exit 0
    fi
    echo "日志推送第 $attempt 次失败，等 3 秒重试"
    sleep 3
done
echo "日志没推上去，跳过"
exit 0
