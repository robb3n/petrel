#!/usr/bin/env bash
# local-deploy.sh — stamp 之后的本地部署（AGENTS.md `## Local Deploy` 只引用本脚本，步骤只在这里写一份）：
# 本机构建 debug 包 → 复制到桌面侧载存档 ~/Desktop/petrel/Petrel-<versionName>.apk → 经 ssh 送到接着手机的主机（默认 keystone）
# → 用那台主机自己的 adb 覆盖安装到手机 → 装之前 Petrel 的 VPN 开着的话，装完从快捷开关把它拉起来（不弹界面）。
#
# 为什么是 debug 包：push-config.sh、netevents.log 都靠 run-as，只对 debuggable 包有效；Petrel 界面轻，不像 Mu3ic 那样
# 需要 release 构建才流畅。为什么在远端跑 adb：同 device-smoke.sh 开头的说明。
#
# 只做本地、可逆的动作。覆盖安装会让正在跑的 VPN 断开十秒左右，装完恢复原状态。
# stdout 只打一行：侧载存档里的 APK 路径；过程日志全走 stderr。远端只用 ~/.cache/petrel-deploy/run.* 临时目录，跑完删掉。
#
# 退出码：
#   0   装好了（VPN 的恢复结果看 stderr 最后的「VPN：」一行：restored / was off / not restored（原因））
#   1   失败：构建、复制、安装失败，或装完 crash buffer 里有本包的 FATAL EXCEPTION；环境变量取值不合法
#   10  设备不可用：远端主机连不上，或手机不在 adb devices 里 / 未授权（APK 已经放进侧载存档）
#
# 环境变量：
#   PETREL_DEPLOY_HOST     接着手机的主机（ssh 目标，可带 user@），默认 keystone
#   PETREL_DEPLOY_SERIAL   手机的 adb 序列号，默认 b65040ad（一加 12）
#   PETREL_DEPLOY_DIR      侧载存档目录，默认 ~/Desktop/petrel
#
# 设备侧入口（本机那一半经 ssh 调用，不要手动跑）：
#   local-deploy.sh --device-check          手机在不在线（不在 → 10）
#   local-deploy.sh --device <apk>          覆盖安装、查崩溃、恢复 VPN
set -uo pipefail

HOST="${PETREL_DEPLOY_HOST:-keystone}"
SERIAL="${PETREL_DEPLOY_SERIAL:-b65040ad}"
DEPLOY_DIR="${PETREL_DEPLOY_DIR:-$HOME/Desktop/petrel}"
PKG="com.robb3n.petrel"
SERVICE="${PKG}/.PetrelVpnService"
TILE="${PKG}/.PetrelTileService"

WHERE=""
case "${1:-}" in --device|--device-check) WHERE="@$(uname -n)" ;; esac
log() { printf 'local-deploy%s: %s\n' "$WHERE" "$*" >&2; }
fail() { log "✗ $*"; exit 1; }
unavailable() { log "✗ 设备不可用：$*"; exit 10; }

# SDK 位置同 device-smoke.sh：已有的 ANDROID_HOME 优先，否则 keystone 的 ~/Android/Sdk，再否则 mac 的 ~/Library/Android/sdk
if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME" ]; then
  ANDROID_HOME="$HOME/Android/Sdk"
  if [ ! -d "$ANDROID_HOME" ] && [ -d "$HOME/Library/Android/sdk" ]; then ANDROID_HOME="$HOME/Library/Android/sdk"; fi
fi
ADB="$ANDROID_HOME/platform-tools/adb"

# ---------------------------------------------------------------------------------------------------
# 设备侧：在接着手机的主机上执行
# ---------------------------------------------------------------------------------------------------
device_preflight() {
  [ -x "$ADB" ] || unavailable "找不到 adb（${ADB}）"
  "$ADB" start-server >/dev/null 2>&1 || true
  local state
  state="$("$ADB" devices 2>/dev/null | tr -d '\r' | awk -v s="$SERIAL" 'NR > 1 && $1 == s { print $2 }')"
  case "$state" in
    device) ;;
    unauthorized) unavailable "${SERIAL} 未授权：在手机上点「允许 USB 调试」" ;;
    '') unavailable "adb devices 里没有 ${SERIAL}（线没插好，或手机没开 USB 调试）" ;;
    *) unavailable "${SERIAL} 的 adb 状态是 ${state}，不是 device" ;;
  esac
}

sh_dev() { "$ADB" -s "$SERIAL" shell "$1" 2>/dev/null | tr -d '\r'; }

# Petrel 的 VPN 在跑：服务在，且有 tun<N> 接口（服务刚起、TUN 还没建好时不算）。
# 只认 tun 加数字：ColorOS 上常驻一个与 VPN 无关的 tunl0（IPIP 隧道，DOWN），`^tun` 会把它也算进去
vpn_running() {
  local svc tun
  svc="$(sh_dev "dumpsys activity services ${SERVICE}")"
  tun="$(sh_dev "ip -br link")"
  case "$svc" in *ServiceRecord*) ;; *) return 1 ;; esac
  grep -qE '^tun[0-9]+[[:space:]]' <<<"$tun"
}

# 锁屏时磁贴和 am start 都会被拦下（docs/lessons/tile-and-device-testing.md）
keyguard_showing() {
  local w
  w="$(sh_dev "dumpsys window")"
  grep -q 'isKeyguardShowing=true' <<<"$w"
}

wait_vpn() {   # wait_vpn <秒>：期间 VPN 起来就返回 0
  local deadline=$(( $(date +%s) + $1 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    vpn_running && return 0
    sleep 1
  done
  return 1
}

device_run() {   # device_run <apk>
  local apk="$1"
  [ -s "$apk" ] || fail "没有收到 APK（${apk}）"
  device_preflight
  log "手机：${SERIAL}（$(sh_dev "getprop ro.product.model")）"

  local was_on=0
  if vpn_running; then was_on=1; fi
  log "装之前 VPN：$([ "$was_on" = 1 ] && echo 开着 || echo 关着)"

  local install_out crash
  "$ADB" -s "$SERIAL" logcat -b crash -c >/dev/null 2>&1 || true
  if ! install_out="$("$ADB" -s "$SERIAL" install -r "$apk" 2>&1 | tr -d '\r')"; then
    printf '%s\n' "$install_out" >&2
    case "$install_out" in
      *INSTALL_FAILED_UPDATE_INCOMPATIBLE*) fail "adb install -r 失败：手机上的 ${PKG} 是另一把 key 签的——构建机的 ~/.android/debug.keystore 要和 keystone 的是同一把" ;;
      *INSTALL_FAILED_VERSION_DOWNGRADE*) fail "adb install -r 失败：手机上的 versionCode 比这个包大（这个包是从更早的提交构建的？）" ;;
      *) fail "adb install -r 失败" ;;
    esac
  fi
  log "$(tail -1 <<<"$install_out")"

  local vpn="was off"
  if [ "$was_on" = 1 ]; then
    # 设了「始终开启的 VPN」时系统会自己拉起；先等一会儿，没起来再点磁贴（磁贴是开关，VPN 已经在跑时点了反而会关掉）
    if wait_vpn 5; then
      vpn="restored"
    elif keyguard_showing; then
      vpn="not restored（手机锁屏，磁贴被拦下：解锁后点一下快捷开关）"
    else
      "$ADB" -s "$SERIAL" shell cmd statusbar click-tile "$TILE" >/dev/null 2>&1
      if wait_vpn 30; then vpn="restored"; else vpn="not restored（点了快捷开关，30 秒内没起来）"; fi
    fi
  fi

  crash="$("$ADB" -s "$SERIAL" logcat -b crash -d 2>/dev/null | tr -d '\r')"
  if grep -q 'FATAL EXCEPTION' <<<"$crash" && grep -q "Process: ${PKG}," <<<"$crash"; then
    grep -A20 'FATAL EXCEPTION' <<<"$crash" >&2
    fail "crash buffer 里有 ${PKG} 的 FATAL EXCEPTION"
  fi
  log "VPN：${vpn}"
  exit 0
}

case "${1:-}" in
  --device-check) device_preflight; exit 0 ;;
  --device) [ $# -eq 2 ] || fail "用法：$0 --device <apk>"; device_run "$2" ;;
  "") ;;
  *) fail "不认识的参数：$1（本机直接跑、不带参数；--device* 只给 ssh 调）" ;;
esac

# ---------------------------------------------------------------------------------------------------
# 本机侧：构建、放进侧载存档、送到 $HOST 装机
# ---------------------------------------------------------------------------------------------------
case "$HOST" in ''|-*|*[!A-Za-z0-9._@-]*) fail "PETREL_DEPLOY_HOST 取值不合法：${HOST}" ;; esac
case "$SERIAL" in ''|-*|*[!A-Za-z0-9._:-]*) fail "PETREL_DEPLOY_SERIAL 取值不合法：${SERIAL}" ;; esac

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
SELF="$SELF_DIR/$(basename "$0")"
ROOT="$(cd "$SELF_DIR/.." && pwd)"
OUT_DIR="$ROOT/app/build/outputs/apk/debug"
APK="$OUT_DIR/app-debug.apk"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4)
REMOTE_ENV="PETREL_DEPLOY_SERIAL=${SERIAL}"

# rsh / rput 同 device-smoke.sh：ssh 自己出错时退出码是 255，一律按设备不可用算
rsh() {
  ssh -n "${SSH_OPTS[@]}" "$HOST" "$1"
  local rc=$?
  [ "$rc" -ne 255 ] || unavailable "ssh ${HOST} 失败（连不上、认证失败或中途断线）"
  return "$rc"
}
rput() {
  ssh "${SSH_OPTS[@]}" "$HOST" "cat > '$2'" < "$1"
  local rc=$?
  [ "$rc" -ne 255 ] || unavailable "ssh ${HOST} 失败（连不上、认证失败或中途断线）"
  return "$rc"
}

RUN=""
cleanup() { if [ -n "$RUN" ]; then ssh -n "${SSH_OPTS[@]}" "$HOST" "rm -rf '$RUN'" >/dev/null 2>&1 || true; fi; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# 1. 构建当前的树（版本号由 git 推出，见 app/build.gradle.kts；要在 stamp 的提交之后构建，build 号才是这个提交的）
log "构建 :app:assembleDebug（${ROOT}）"
( cd "$ROOT" && unset http_proxy https_proxy all_proxy && ./gradlew :app:assembleDebug -q ) >&2 || fail "构建失败"
[ -s "$APK" ] || fail "构建完了却没有 ${APK}"
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$OUT_DIR/output-metadata.json" 2>/dev/null)"
case "$version" in ''|*[!A-Za-z0-9._-]*) fail "读不出 versionName（${OUT_DIR}/output-metadata.json）" ;; esac

# 2. 放进侧载存档（同名说明同一个 build 号，覆盖即可）
mkdir -p "$DEPLOY_DIR" || fail "建不了 ${DEPLOY_DIR}"
dest="$DEPLOY_DIR/Petrel-${version}.apk"
cp "$APK" "$dest" || fail "复制到 ${dest} 失败"
log "侧载存档：${dest}"

# 3. 送到 $HOST，覆盖安装到手机并恢复 VPN
log "连接 ${HOST}"
RUN="$(rsh 'mkdir -p "$HOME/.cache/petrel-deploy" && mktemp -d "$HOME/.cache/petrel-deploy/run.XXXXXX"')" || exit $?
case "$RUN" in
  /*/.cache/petrel-deploy/run.*) case "$RUN" in *[!A-Za-z0-9._/-]*) RUN=""; fail "远端临时目录路径含意外字符" ;; esac ;;
  *) bad="$RUN"; RUN=""; fail "远端没有建出临时目录（得到：${bad}）" ;;
esac
rput "$SELF" "$RUN/local-deploy.sh" || fail "脚本送不到 ${HOST}:${RUN}"
rsh "${REMOTE_ENV} bash '$RUN/local-deploy.sh' --device-check" || exit $?
rput "$APK" "$RUN/app-debug.apk" || fail "APK 送不到 ${HOST}:${RUN}"
rsh "${REMOTE_ENV} bash '$RUN/local-deploy.sh' --device '$RUN/app-debug.apk'" || exit $?

log "✓ 已装到 ${SERIAL}：Petrel ${version}"
printf '%s\n' "$dest"
exit 0
