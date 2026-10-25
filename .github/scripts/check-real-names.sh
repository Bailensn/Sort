#!/usr/bin/env bash
# 真实人名检查。
#
# 为什么名单不在仓库里：把要禁止的名单写进仓库，本身就是一次泄漏。
# 所以这里只存 sha256，名单由 secret REAL_NAME_HASHES 提供（空格或换行分隔）。
# 没配 secret 时跳过（本地开发、fork 的 PR 都不会误伤）。
set -uo pipefail

hashes="${REAL_NAME_HASHES:-}"
if [[ -z "${hashes//[[:space:]]/}" ]]; then
  echo "未配置 REAL_NAME_HASHES，跳过人名检查"
  exit 0
fi

hash_file="$(mktemp)"
printf '%s\n' "$hashes" | tr -s '[:space:]' '\n' | sed '/^$/d' > "$hash_file"
trap 'rm -f "$hash_file"' EXIT

found=0
while IFS= read -r file; do
  # 抽出所有 2~4 个汉字的片段（人名长度范围），去重后逐个比对。
  while IFS= read -r token; do
    [[ -z "$token" ]] && continue
    digest="$(printf 'sort:%s' "$token" | sha256sum | cut -d' ' -f1)"
    if grep -qxF "$digest" "$hash_file"; then
      echo "::error file=$file::检测到疑似真实人名（已按规则屏蔽具体内容）"
      found=1
    fi
  done < <(grep -oP '[\x{4e00}-\x{9fa5}]{2,4}' "$file" 2>/dev/null | sort -u || true)
done < <(git ls-files '*.kt' '*.java' '*.xml' '*.md' '*.txt' '*.kts' '*.yml' '*.yaml' '*.json')

if [[ "$found" -eq 1 ]]; then
  echo "::error::仓库里出现了真实人名，提交被拒绝。请换成占位名（见 AGENTS.md）"
  exit 1
fi
echo "人名检查通过"
