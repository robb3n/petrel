#!/usr/bin/env bash
# Repository-side domestic-rule maintenance; relative --dir paths are relative to core/.
set -euo pipefail
cd "$(dirname "$0")/../core"
export PATH="/opt/homebrew/bin:/usr/local/go/bin:$PATH"
exec go run ./cmd/cn-rules "$@"
