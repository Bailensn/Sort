#!/usr/bin/env bash
# 把各 job 上传的错误报告合并成一条提交评论 + 一份总 Summary。
# 各 job 只负责跑任务、抓日志；评论只在最后这一个 job 里发一次。
set -uo pipefail

SRC="${1:-logs}"
OUT="ci-report/all-errors.md"
mkdir -p ci-report

found=0
for f in "$SRC"/*/errors.md; do
  [[ -f "$f" ]] && found=1
done

if [[ "$found" -eq 0 ]]; then
  echo "没有失败报告，跳过。"
  exit 0
fi

{
  echo "## 构建失败"
  echo
  echo "| 项 | 值 |"
  echo "| --- | --- |"
  echo "| 仓库 | \`${GITHUB_REPOSITORY:-}\` |"
  echo "| 提交 | \`${GITHUB_SHA:-}\` |"
  echo "| 运行 | ${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-0} |"
  echo
  for f in "$SRC"/*/errors.md; do
    [[ -f "$f" ]] || continue
    cat "$f"
    echo
  done
} > "$OUT"

cat "$OUT"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  cat "$OUT" >> "$GITHUB_STEP_SUMMARY"
fi

# 公开仓库的提交评论无需登录即可读取，Agent 和人都不用翻日志。
if [[ -n "${GITHUB_TOKEN:-}" && -n "${GITHUB_SHA:-}" ]] && command -v python3 >/dev/null 2>&1; then
  payload="$(python3 -c 'import json,sys; print(json.dumps({"body": open(sys.argv[1], encoding="utf-8").read()}))' "$OUT")"
  code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $GITHUB_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -d "$payload" \
    "https://api.github.com/repos/$GITHUB_REPOSITORY/commits/$GITHUB_SHA/comments" || true)"
  echo "commit comment: $code"
fi

exit 0
