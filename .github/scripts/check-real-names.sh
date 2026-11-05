#!/usr/bin/env bash
# 真实人名检查。
#
# 为什么名单不在仓库里：把要禁止的名单写进仓库，本身就是一次泄漏。
# 所以这里只存 sha256，名单由 secret REAL_NAME_HASHES 提供（空格或换行分隔）。
# 没配 secret 时跳过（本地开发、fork 的 PR 都不会误伤）。
set -uo pipefail

hashes="${REAL_NAME_HASHES:-}"
hash_file="$(mktemp)"
trap 'rm -f "$hash_file"' EXIT
printf '%s\n' "$hashes" | tr -s '[:space:]' '\n' | sed '/^$/d' > "$hash_file"

found=0
if [[ -s "$hash_file" ]]; then
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
else
  echo "未配置 REAL_NAME_HASHES，跳过人名检查（学号检查照常执行）"
fi

# 学号检查：10~13 位纯数字，只允许 9000 / 0000 开头的假号。
while IFS= read -r file; do
  while IFS= read -r line; do
    number="${line#*:}"
    case "$number" in
      9000*|0000*) continue ;;
    esac
    echo "::error file=$file::出现疑似真实学号（${#number} 位数字），请改用 9000 开头的假号"
    found=1
  done < <(grep -oE '[0-9]{10,13}' "$file" 2>/dev/null | sort -u | sed "s|^|$file:|" || true)
done < <(git ls-files '*.kt' '*.java' '*.xml' '*.md' '*.txt' '*.kts' '*.yml' '*.yaml' '*.json')

if [[ "$found" -eq 1 ]]; then
  echo "::error::仓库里出现了真实人名，提交被拒绝。请换成占位名（见 AGENTS.md）"
  exit 1
fi
echo "人名/学号检查通过"
