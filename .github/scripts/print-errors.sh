#!/usr/bin/env bash
# 构建失败时，把关键错误直接打在当前 step 的输出里，并生成 ::error 注解。
# 用法：print-errors.sh [build.log]
set -uo pipefail

LOG="${1:-build.log}"
if [[ ! -f "$LOG" ]]; then
  echo "::error::构建失败，而且没有拿到 $LOG"
  exit 0
fi

relpath() {
  local p="$1"
  if [[ -n "${GITHUB_WORKSPACE:-}" && "$p" == "$GITHUB_WORKSPACE"/* ]]; then
    printf '%s' "${p#"$GITHUB_WORKSPACE"/}"
  else
    printf '%s' "$p"
  fi
}

echo "::group::构建错误"
grep -E '^e: |: error: |^> Task .* FAILED|^\* What went wrong|^> A failure|^> Execution failed' "$LOG" | head -40 || true
echo "::endgroup::"

# 带文件行号的错误转成注解：PR/commit 页面和 run 页面都能直接看到。
emitted=0
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  clean="${line#e: }"
  clean="${clean#file://}"
  if [[ "$clean" =~ ^(.+):([0-9]+):([0-9]+)[[:space:]](.*)$ ]]; then
    echo "::error file=$(relpath "${BASH_REMATCH[1]}"),line=${BASH_REMATCH[2]},col=${BASH_REMATCH[3]}::${BASH_REMATCH[4]}"
    emitted=$((emitted + 1))
  fi
  [[ $emitted -ge 10 ]] && break
done < <(grep -E '^e: (file://)?[^ ]+:[0-9]+:[0-9]+ ' "$LOG" || true)

# Kotlin 2.4 的 Build Tools API 只给 Location，没有消息文本。
while IFS= read -r loc; do
  [[ -z "$loc" ]] && continue
  path="${loc#Location: }"
  echo "::error file=$(relpath "${path%:[0-9]*}"),line=${path##*:}::Kotlin 编译错误"
  emitted=$((emitted + 1))
  [[ $emitted -ge 10 ]] && break
done < <(grep -oE 'Location: [^ ]+\.(kt|java):[0-9]+' "$LOG" | sort -u || true)

# 没有解析到结构化错误时，至少把 Gradle 失败原因和日志末尾打出来。
if [[ $emitted -eq 0 ]]; then
  echo "::group::Gradle 失败原因"
  awk '/^\* What went wrong:/{flag=1} flag{print} /^\* Try:/{if(flag) exit}' "$LOG" | head -30 || true
  echo "::endgroup::"
  echo "::group::日志末尾"
  tail -30 "$LOG"
  echo "::endgroup::"
  # 把 Gradle 失败原因的头几行直接写进注解：run 页面上不用展开日志就能看到，
  # 配置阶段（脚本编译错误、插件解析失败）也能定位。
  summary="$(awk '/^\* What went wrong:/{flag=1;next} /^\* Try:/{exit} flag && NF {print; count++} count>=3{exit}' "$LOG" | tr '\n' ' ')"
  summary="${summary//\%/%25}"
  summary="${summary:0:400}"
  if [[ -n "$summary" ]]; then
    echo "::error::构建失败：$summary"
  else
    echo "::error::构建失败，原因见上方输出"
  fi
else
  echo "::error::构建失败，$emitted 处错误已标注到文件行"
fi
