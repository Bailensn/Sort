#!/usr/bin/env bash
# 四个 Android job 共用的 SDK 组件安装；带一次重试，失败时把日志尾巴打出来。
# 用法：install-sdk-components.sh [ndk]
set -o pipefail

need_ndk="${1:-}"

components=("platforms;android-37.0" "build-tools;36.0.0")
if [ "$need_ndk" = "ndk" ]; then
    components+=("cmake;3.22.1" "ndk;30.0.16248370")
fi

yes | sdkmanager --licenses > /dev/null || true

installed=""
for attempt in 1 2; do
    if sdkmanager --install "${components[@]}" > sdk-install.log 2>&1; then
        installed=1
        break
    fi
    echo "第 $attempt 次安装失败，5 秒后重试"
    tail -5 sdk-install.log || true
    sleep 5
done

if [ -z "$installed" ]; then
    tail -20 sdk-install.log || true
    echo "::error::SDK 组件安装失败：$(tail -3 sdk-install.log | tr '\n' ' ')"
    exit 1
fi
