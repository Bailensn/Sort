#!/usr/bin/env bash
# 从 Gradle 日志里提取结构化错误，供单个 job 使用：
#   1. 写进本 job 的 GitHub Actions Job Summary（网页上直接看）
#   2. 生成 ::error 注解（PR/commit 页面能点）
#   3. 落盘 ci-report/errors.md，由汇总 job 合并成一条提交评论
#
# 用法：report-errors.sh <build.log> [小节标题]
set -uo pipefail

LOG="${1:-build.log}"
TITLE="${2:-构建}"
REPORT_DIR="ci-report"
REPORT="$REPORT_DIR/errors.md"
mkdir -p "$REPORT_DIR"

# 环境准备阶段（装 SDK、缓存）就失败时还没有 build.log，
# 也要写一份最小报告，否则汇总 job 收不到任何 artifact，提交评论也不会出现。
if [[ ! -f "$LOG" ]]; then
  {
    echo "### $TITLE"
    echo
    echo "这个 job 在产出构建日志之前就失败了，问题多半在环境准备步骤。"
    echo
    echo "| 项 | 值 |"
    echo "| --- | --- |"
    echo "| job | \`${GITHUB_JOB:-unknown}\` |"
    echo "| runner | \`${ImageOS:-unknown} ${ImageVersion:-}\` |"
    echo "| 日志 | 需要登录 GitHub 才能看该 job 的原始日志 |"
  } > "$REPORT"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    cat "$REPORT" >> "$GITHUB_STEP_SUMMARY"
  fi
  cat "$REPORT"
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

{
  echo "### $TITLE"
  echo
  echo "| 项 | 值 |"
  echo "| --- | --- |"
  echo "| 结构化错误 | $count 条 |"
  echo
} > "$REPORT"

if [[ -n "$failed_tasks" ]]; then
  {
    echo "**失败的任务**"
    echo '```text'
    echo "$failed_tasks"
    echo '```'
    echo
  } >> "$REPORT"
fi

if [[ -n "$kotlin_locations" ]]; then
  {
    echo "**Kotlin 错误位置**"
    echo '```text'
    echo "$kotlin_locations"
    echo '```'
    echo
  } >> "$REPORT"
fi

if [[ -n "$kotlin_errors" ]]; then
  {
    echo "**Kotlin 编译错误**"
    echo '```text'
    echo "$kotlin_errors"
    echo '```'
    echo
  } >> "$REPORT"
fi

if [[ -n "$java_errors" ]]; then
  {
    echo "**Java 编译错误**"
    echo '```text'
    echo "$java_errors"
    echo '```'
    echo
  } >> "$REPORT"
fi

if [[ -n "$toolchain" ]]; then
  {
    echo "**依赖 / 工具链**"
    echo '```text'
    echo "$toolchain"
    echo '```'
    echo
  } >> "$REPORT"
fi

if [[ -n "$gradle_block" ]]; then
  {
    echo "**Gradle 失败原因**"
    echo '```text'
    echo "$gradle_block"
    echo '```'
    echo
  } >> "$REPORT"
fi

if [[ "$count" -eq 0 && -z "$gradle_block" ]]; then
  echo "没有解析到结构化错误，下面是日志末尾，完整内容见 artifact。" >> "$REPORT"
  echo >> "$REPORT"
fi

{
  echo "**日志末尾**"
  echo '```text'
  tail -40 "$LOG"
  echo '```'
} >> "$REPORT"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  cat "$REPORT" >> "$GITHUB_STEP_SUMMARY"
fi
printf '\n===== BUILD ERRORS =====\n'
cat "$REPORT"

# 注解里的路径要转义逗号和百分号，否则带逗号的路径会截断注解。
escape_annotation() {
  local s="$1"
  s="${s//'%'/'%25'}"
  s="${s//$'\r'/}"
  s="${s//$'\n'/%0A}"
  s="${s//','/'%2C'}"
  printf '%s' "$s"
}

i=0
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  clean="${line#e: }"
  clean="${clean#file://}"
  if [[ "$clean" =~ ^(.+):([0-9]+):([0-9]+)[[:space:]](.*)$ ]]; then
    path="${BASH_REMATCH[1]}"
    rel="${path#"$workspace"/}"
    echo "::error file=$(escape_annotation "$rel"),line=${BASH_REMATCH[2]},col=${BASH_REMATCH[3]}::$(escape_annotation "${BASH_REMATCH[4]}")"
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
  echo "::error file=$(escape_annotation "$path"),line=$line_no::Kotlin 编译错误，详情见 Job Summary"
  i=$((i + 1))
  [[ $i -ge 15 ]] && break
done <<< "$kotlin_locations"

exit 0
