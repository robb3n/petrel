#!/usr/bin/env bash
# 用 gomobile 把 ptcore 编成 Android AAR（arm64 真机 + x86_64 模拟器）。
# 构建标签同 CMFA：with_gvisor（gVisor 栈）+ cmfa（TUN 不读 /data/system/packages.xml——普通 App 无权读，
# 不加时 mihomo 建 TUN 直接失败；同时关掉进程名查找、回环检测，REST 进入 embed 模式）。
# 用法：core/build.sh [输出 AAR 路径]，默认 app/libs/ptcore.aar。Gradle 的 buildCore 任务会调用它。
set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
: "${ANDROID_NDK_HOME:=$ANDROID_HOME/ndk/29.0.14206865}"
export ANDROID_HOME ANDROID_NDK_HOME
# Gradle 守护进程可能不带登录 shell 的 PATH，补上常见的 Go 安装位置
export PATH="/opt/homebrew/bin:/usr/local/go/bin:$PATH"
export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"
export PATH="$(go env GOPATH)/bin:$PATH"

if ! command -v gomobile >/dev/null; then
  echo "gomobile 未安装：go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest && gomobile init" >&2
  exit 1
fi

out="${1:-../app/libs/ptcore.aar}"
mkdir -p "$(dirname "$out")"
gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi 29 \
  -tags with_gvisor,cmfa \
  -trimpath \
  -ldflags="-s -w -buildid=" \
  -javapkg com.robb3n.petrel.core \
  -o "$out" \
  ./ptcore
