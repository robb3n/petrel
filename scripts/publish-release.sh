#!/usr/bin/env bash
# publish-release.sh — 对外发布（AGENTS.md `## Release` 只引用本脚本，步骤只在这里写一份）：
# 核对 HEAD 是打过 tag 的稳定版且已推送 → 本机构建 release 包 → 校验版本、签名指纹、ABI、非 debuggable
# → 产出 Petrel-<版本>.apk 与 .sha256 → 建 GitHub Release（tag v<版本>）并附上两者 → 下载回来核对 sha256。
#
# 对外、不可逆的只有第 5 步起（建 Release、上传），--dry-run 只做前四步，零远端写入。
# 同名 Release 已存在时拒绝，不覆盖、不改附件：发错了由人到 GitHub 上处理。
# stdout 只打一行：正式发布打 Release 的网址，--dry-run 打本机 APK 路径；过程日志全走 stderr。
#
# 用法：scripts/publish-release.sh --notes-file <文件> [--dry-run]
#   --notes-file   Release 正文（面向用户的中文更新说明，纯文本 / Markdown）
#   --dry-run      只构建与校验，不碰 GitHub
#
# 退出码：0 发布完成（或 dry-run 校验通过）；1 任一前置条件、构建、校验或上传失败
set -uo pipefail

# 正式证书 SHA-256（release key 见 AGENTS.md「## Release」）；换 key = 已装用户无法覆盖升级，别换。
OFFICIAL_CERT_SHA256="94096a8fa7821aab4437210421bd4879304cffab8f3d52d98a7eef2506038a7d"
REPO="robb3n/petrel"
PKG="com.robb3n.petrel"

log() { printf 'publish-release: %s\n' "$*" >&2; }
fail() { log "✗ $*"; exit 1; }

NOTES="" DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --notes-file) NOTES="${2:-}"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) fail "不认识的参数：$1" ;;
  esac
done
[ -n "$NOTES" ] && [ -s "$NOTES" ] || fail "缺 --notes-file，或文件为空"

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)" || fail "不在 git 仓库里"
cd "$ROOT" || fail "进不了 $ROOT"

if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME" ]; then ANDROID_HOME="$HOME/Library/Android/sdk"; fi
BT="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -x "$BT/apksigner" ] && [ -x "$BT/aapt2" ] || fail "找不到 build-tools 里的 apksigner / aapt2（ANDROID_HOME=$ANDROID_HOME）"

# 1. 前置：已跟踪文件干净、HEAD 上有 v<baseVersionName>、分支与 tag 都已推到 origin
base="$(sed -n 's/^val baseVersionName = "\(.*\)"$/\1/p' app/build.gradle.kts)"
[ -n "$base" ] || fail "读不出 app/build.gradle.kts 的 baseVersionName"
tag="v$base"
git diff --quiet HEAD || fail "已跟踪文件有未提交改动"
git tag --points-at HEAD | grep -qx "$tag" || fail "HEAD 上没有 tag $tag（tag 由回火任务的 stamp 打）"
git fetch -q origin || fail "git fetch 失败"
[ "$(git rev-parse HEAD)" = "$(git rev-parse '@{upstream}' 2>/dev/null)" ] || fail "HEAD 与上游分支不一致，先 push"
remote_tag="$(git ls-remote origin "refs/tags/$tag" | cut -f1)"
[ "$remote_tag" = "$(git rev-parse HEAD)" ] || fail "origin 上的 $tag 不存在或不指向 HEAD"
log "前置通过：$tag @ $(git rev-parse --short HEAD)"

# 2. 构建（缺 petrel.release.* 签名属性时 gradle 直接失败，不会退回 debug key）
( unset http_proxy https_proxy all_proxy && ./gradlew :app:assembleRelease -q ) >&2 || fail "构建失败"
OUT_DIR="app/build/outputs/apk/release"
APK="$OUT_DIR/app-release.apk"
[ -f "$APK" ] || fail "没有产出 $APK"

# 3. 校验：版本、签名、ABI、非 debuggable
badging="$("$BT/aapt2" dump badging "$APK")" || fail "aapt2 读不了 APK"
name="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")"
ver="$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
code="$(sed -n "s/.* versionCode='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
[ "$name" = "$PKG" ] || fail "包名是 $name，不是 $PKG"
[ "$ver" = "$base" ] || fail "versionName 是 $ver，不是稳定版 $base"
grep -q "application-debuggable" <<<"$badging" && fail "APK 是 debuggable 的"
cert="$("$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[ "$cert" = "$OFFICIAL_CERT_SHA256" ] || fail "签名指纹 ${cert:-读不出} 不是正式证书"
abis="$(unzip -Z1 "$APK" 'lib/*' 2>/dev/null | cut -d/ -f2 | sort -u | tr '\n' ' ')"
[ "$abis" = "arm64-v8a " ] || fail "APK 里的 ABI 是「${abis}」，应只有 arm64-v8a"
log "校验通过：$PKG $ver（build $code），正式签名，arm64-v8a"

# 4. 产物：Petrel-<版本>.apk 与它的 sha256
DIST="$ROOT/app/build/dist"
mkdir -p "$DIST"
asset="$DIST/Petrel-$ver.apk"
cp "$APK" "$asset" || fail "复制 APK 失败"
sha="$(shasum -a 256 "$asset" | cut -d' ' -f1)"
printf '%s  %s\n' "$sha" "Petrel-$ver.apk" > "$asset.sha256"
log "产物：$asset（sha256 $sha）"

if [ "$DRY" = 1 ]; then
  log "dry-run：到此为止，没有碰 GitHub"
  echo "$asset"
  exit 0
fi

# 5. 建 Release 并上传（对外、不可逆）
gh release view "$tag" -R "$REPO" >/dev/null 2>&1 && fail "GitHub 上已有 Release $tag，不覆盖"
gh release create "$tag" -R "$REPO" --verify-tag --title "Petrel $ver" --notes-file "$NOTES" \
  "$asset" "$asset.sha256" >&2 || fail "gh release create 失败（若已建出一半，到 GitHub 上看 $tag）"

# 6. 回读核对
CHECK="$(mktemp -d)"
trap 'rm -rf "$CHECK"' EXIT
gh release download "$tag" -R "$REPO" -p "Petrel-$ver.apk" -D "$CHECK" >&2 || fail "回读下载失败，到 GitHub 上核对 $tag 的附件"
[ "$(shasum -a 256 "$CHECK/Petrel-$ver.apk" | cut -d' ' -f1)" = "$sha" ] || fail "回读的 APK sha256 不一致，到 GitHub 上核对"
url="$(gh release view "$tag" -R "$REPO" --json url -q .url)"
log "✓ 已发布 $tag，回读 sha256 一致"
echo "$url"
