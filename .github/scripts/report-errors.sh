#!/usr/bin/env bash
# 从 Gradle 日志里提取结构化错误：
#   1. 写进 GitHub Actions Job Summary（网页上直接看）
#   2. 生成 ::error 注解（PR/commit 页面能看到文件行号）
#   3. 落盘 ci-report/errors.md，随 artifact 上传
set -uo pipefail

LOG="${1:-build.log}"
REPORT_DIR="ci-report"
REPORT="$REPORT_DIR/errors.md"
mkdir -p "$REPORT_DIR"

if [[ ! -f "$LOG" ]]; then
  echo "未找到构建日志：$LOG" >&2
  exit 0
fi

kotlin_errors="$(grep -E '^e: (file://)?[^ ]+:[0-9]+:[0-9]+ ' "$LOG" | head -60 || true)"
# Kotlin 2.4 用 Build Tools API 报错时不再输出 e: 行，而是把位置单独写成
# “Location: /路径/File.kt:行”。两种格式都要认。
kotlin_locations="$(grep -oE 'Location: [^ ]+\.(kt|java):[0-9]+' "$LOG" | sed 's/^Location: //' | sort -u | head -40 || true)"
java_errors="$(grep -E '\.java:[0-9]+: error: ' "$LOG" | head -40 || true)"
failed_tasks="$(grep -E '^> Task .* FAILED' "$LOG" | head -20 || true)"
toolchain="$(grep -E 'Could not (resolve|find|download)|No matching toolchains|Unsupported class file|Could not determine the dependencies' "$LOG" | head -20 || true)"
gradle_block="$(awk '/^\* What went wrong:/{flag=1} flag{print} /^\* Try:/{if(flag) exit}' "$LOG" | head -50 || true)"

count="$(printf '%s\n%s\n%s\n' "$kotlin_errors" "$kotlin_locations" "$java_errors" | grep -c '[^[:space:]]' || true)"

workspace="${GITHUB_WORKSPACE:-}"
repo="${GITHUB_REPOSITORY:-local}"
sha="${GITHUB_SHA:-unknown}"
run_url="${GITHUB_SERVER_URL:-https://github.com}/$repo/actions/runs/${GITHUB_RUN_ID:-0}"

{
  echo "## 构建失败"
  echo
  echo "| 项 | 值 |"
  echo "| --- | --- |"
  echo "| 仓库 | \`$repo\` |"
  echo "| 提交 | \`$sha\` |"
  echo "| 运行 | $run_url |"
  echo "| 结构化错误 | $count 条 |"
  echo
} > "$REPORT"

if [[ -n "$failed_tasks" ]]; then
  {
    echo "### 失败的 Task"
    echo '```text'
    echo "$failed_tasks"
    echo '```'
    echo
  } >> "$REPORT"
fi

if [[ -n "$kotlin_locations" ]]; then
  {
    echo "### Kotlin 错误位置"
    echo '```text'
    echo "$kotlin_locations"
    echo '```'
    echo
  } >> "$REPORT"
fi

if [[ -n "$kotlin_errors" ]]; then
  {
    echo "### Kotlin 编译错误"
    echo '```text'
    echo "$kotlin_errors"
    echo '```'
    echo
  } >> "$REPORT"
fi

if [[ -n "$java_errors" ]]; then
  {
    echo "### Java 编译错误"
    echo '```text'
    echo "$java_errors"
    echo '```'
    echo
  } >> "$REPORT"
fi

if [[ -n "$toolchain" ]]; then
  {
    echo "### 依赖 / 工具链"
    echo '```text'
    echo "$toolchain"
    echo '```'
    echo
  } >> "$REPORT"
fi

if [[ -n "$gradle_block" ]]; then
  {
    echo "### Gradle 失败原因"
    echo '```text'
    echo "$gradle_block"
    echo '```'
    echo
  } >> "$REPORT"
fi

if [[ "$count" -eq 0 && -z "$gradle_block" ]]; then
  echo "没有解析到结构化错误，下面是日志末尾，完整内容见 \`build-log\` artifact。" >> "$REPORT"
fi

{
  echo "### 日志末尾"
  echo '```text'
  tail -40 "$LOG"
  echo '```'
} >> "$REPORT"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  cat "$REPORT" >> "$GITHUB_STEP_SUMMARY"
fi
printf '\n===== BUILD ERRORS =====\n'
cat "$REPORT"

# 把 Kotlin 错误转成注解，最多 15 条，避免刷屏。
i=0
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  clean="${line#e: }"
  clean="${clean#file://}"
  if [[ "$clean" =~ ^(.+):([0-9]+):([0-9]+)[[:space:]](.*)$ ]]; then
    path="${BASH_REMATCH[1]}"
    line_no="${BASH_REMATCH[2]}"
    col_no="${BASH_REMATCH[3]}"
    msg="${BASH_REMATCH[4]}"
    rel="${path#"$workspace"/}"
    msg="${msg//'%'/'%25'}"
    msg="${msg//$'\r'/}"
    msg="${msg//$'\n'/%0A}"
    echo "::error file=$rel,line=$line_no,col=$col_no::$msg"
    i=$((i + 1))
  fi
  [[ $i -ge 15 ]] && break
done <<< "$kotlin_errors"

# 新版 Kotlin 只给 Location，没有消息文本，用固定文案标注位置。
while IFS= read -r loc; do
  [[ -z "$loc" ]] && continue
  rel="${loc#"$workspace"/}"
  path="${rel%:[0-9]*}"
  line_no="${rel##*:}"
  echo "::error file=$path,line=$line_no::Kotlin 编译错误，详情见 Job Summary"
  i=$((i + 1))
  [[ $i -ge 15 ]] && break
done <<< "$kotlin_locations"

# 把报告贴到本次提交的评论里：公开仓库无需登录即可读取，Agent 和人都能直接看。
if [[ -n "${GITHUB_TOKEN:-}" && -n "${GITHUB_REPOSITORY:-}" && -n "${GITHUB_SHA:-}" ]] && command -v python3 >/dev/null 2>&1; then
  payload="$(python3 -c 'import json,sys; print(json.dumps({"body": open(sys.argv[1], encoding="utf-8").read()}))' "$REPORT")"
  code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $GITHUB_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -d "$payload" \
    "https://api.github.com/repos/$GITHUB_REPOSITORY/commits/$GITHUB_SHA/comments" || true)"
  echo "commit comment: $code"
fi

exit 0
