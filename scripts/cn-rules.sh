#!/usr/bin/env bash
# Repository-side domestic-rule maintenance; relative --dir paths are relative to core/.
set -euo pipefail
cd "$(dirname "$0")/../core"
export PATH="/opt/homebrew/bin:/usr/local/go/bin:$PATH"
# Same default as core/build.sh: Gradle runs verify on every build, and a cold module cache must not hang on proxy.golang.org.
export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"
exec go run ./cmd/cn-rules "$@"
