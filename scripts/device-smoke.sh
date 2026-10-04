#!/usr/bin/env bash
# device-smoke.sh — 本机构建 debug 包，送到跑模拟器的主机（默认 keystone）上装机冒烟：本机构建 → APK 与本脚本
# 经 ssh 送过去 → 远端确认 AVD 在线（不在就无窗口拉起）→ install -r → 清 logcat → am start → 等几秒确认进程还活着、
# crash buffer 里没有本包的 FATAL EXCEPTION → 截一张图 → 取回本机。
# AGENTS.md `## Device Smoke` 与「check 阶段」第 1–3 步共用这一份，别再各写一份步骤。
#
# 为什么是「送过去、在远端跑 adb」而不是把远端 adb 端口隧道到本机：模拟器本来就得经 ssh 在远端拉起；
# `adb emu` 走模拟器 console 端口、不经 adb server，只转 5037 的隧道会让它静默失效；两台机器的 adb 各随 SDK 升级，
# 版本一旦不一致，本机 client 会去重启 server——隧道下就是重启远端的 adb server。所以设备侧步骤只在远端、用远端自己的 adb 跑。
#
# 可撤销、会终止、不需要交互（ssh 用 BatchMode，要密码就当连不上）。stdout 只打一行：本机截图路径
# （app/build/smoke/ 下，不进 git）；过程日志全走 stderr（远端那段经 ssh 转回本机 stderr）。
# 远端只写 ~/.cache/petrel-smoke/：每次一个 run.* 临时目录（放 APK、脚本和截图，跑完删掉），以及本脚本拉起模拟器时的
# emulator.log（留着排查）。
#
# 退出码：
#   0   通过（截图路径在 stdout；调用方仍要亲自看一眼截图，退出码判不出白屏 / 错误页）
#   1   失败：构建、安装、启动失败，或进程没活下来 / crash buffer 有本包的 FATAL EXCEPTION，或截图失败；环境变量取值不合法
#   10  设备不可用：远端主机连不上（ssh 超时、认证失败、中途断线）、远端没有 adb / emulator / 这个 AVD、
#       模拟器拉不起来（进程提前退出）、开机超时；指定了 PETREL_SMOKE_SERIAL 而那台设备不在线 / 未授权
#
# 环境变量：
#   PETREL_SMOKE_HOST     跑模拟器的主机（ssh 目标，可带 user@），默认 keystone
#   PETREL_SMOKE_SERIAL   改在这台 adb 设备上跑（实体机，或指定某台模拟器）：不查 AVD、不拉模拟器，设备不在线就是 10；
#                        实体机会先亮屏、尝试解开无密码锁屏。默认空 = 用 PETREL_SMOKE_AVD 那台模拟器
#   PETREL_SMOKE_AVD      目标 AVD 名，默认 a35（设了 PETREL_SMOKE_SERIAL 时不用）
#   PETREL_SMOKE_BOOT     等开机的上限秒数，默认 180
#   PETREL_SMOKE_SETTLE   启动后等几秒再查进程与 crash，默认 5
#
# 已经在跑的模拟器不会被关掉；是本脚本拉起的也留着（下次直接复用）。
#
# 设备侧入口（本机那一半经 ssh 调用，不要手动跑）：
#   device-smoke.sh --device-check           远端有没有 adb / emulator / 这个 AVD，或指定的设备在不在线（没有 → 10）
#   device-smoke.sh --device <apk> <目录>    装机、启动、查崩溃、截图到 <目录>，stdout 打截图路径
set -uo pipefail

HOST="${PETREL_SMOKE_HOST:-keystone}"
SERIAL="${PETREL_SMOKE_SERIAL:-}"
AVD="${PETREL_SMOKE_AVD:-a35}"
BOOT_LIMIT="${PETREL_SMOKE_BOOT:-180}"
SETTLE="${PETREL_SMOKE_SETTLE:-5}"
PKG="com.robb3n.petrel"
ACTIVITY="${PKG}/.MainActivity"

WHERE=""
case "${1:-}" in --device|--device-check) WHERE="@$(uname -n)" ;; esac
log() { printf 'device-smoke%s: %s\n' "$WHERE" "$*" >&2; }
fail() { log "✗ $*"; exit 1; }
unavailable() { log "✗ 设备不可用：$*"; exit 10; }

# SSH 非登录 shell 不读 ~/.zshrc，所以 SDK 位置在这里定：已有且存在的 ANDROID_HOME 优先，
# 否则 keystone 的 ~/Android/Sdk，再否则 mac 的 ~/Library/Android/sdk（AGENTS.md「Dev / build / run」的表）。
if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME" ]; then
  ANDROID_HOME="$HOME/Android/Sdk"
  if [ ! -d "$ANDROID_HOME" ] && [ -d "$HOME/Library/Android/sdk" ]; then ANDROID_HOME="$HOME/Library/Android/sdk"; fi
fi
export ANDROID_HOME

# ---------------------------------------------------------------------------------------------------
# 设备侧：在跑模拟器的主机上执行
# ---------------------------------------------------------------------------------------------------
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"

device_preflight() {
  [ -x "$ADB" ] || unavailable "找不到 adb（${ADB}）"
  if [ -n "$SERIAL" ]; then
    # 按 adb devices 的状态列判，不用 get-state：unauthorized / offline 时它只往 stderr 报错，拿不到状态名
    "$ADB" start-server >/dev/null 2>&1 || true
    local state
    state="$("$ADB" devices 2>/dev/null | tr -d '\r' | awk -v s="$SERIAL" 'NR > 1 && $1 == s { print $2 }')"
    case "$state" in
      device) return 0 ;;
      unauthorized) unavailable "${SERIAL} 未授权：在设备上点「允许 USB 调试」" ;;
      '') unavailable "adb devices 里没有 ${SERIAL}（线没插好，或设备没开 USB 调试）" ;;
      *) unavailable "${SERIAL} 的 adb 状态是 ${state}，不是 device" ;;
    esac
  fi
  [ -x "$EMULATOR" ] || unavailable "找不到 emulator（${EMULATOR}）"
  # 先取全文再比，不接 `| grep -q`：pipefail 下 grep 提前退出会让上游吃 SIGPIPE，把「找到了」判成失败
  local avds
  avds="$("$EMULATOR" -list-avds 2>/dev/null | tr -d '\r')"
  case $'\n'"$avds"$'\n' in
    *$'\n'"$AVD"$'\n'*) ;;
    *) unavailable "没有名为 ${AVD} 的 AVD（emulator -list-avds 里没有）" ;;
  esac
}

avd_of() {   # avd_of <serial>：这台模拟器跑的是哪个 AVD
  local name
  name="$("$ADB" -s "$1" shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')"
  if [ -z "$name" ]; then name="$("$ADB" -s "$1" emu avd name 2>/dev/null | head -1 | tr -d '\r')"; fi
  printf '%s' "$name"
}
find_serial() {   # 在线（state=device）且跑着 $AVD 的模拟器 serial；没有就空
  local s
  for s in $("$ADB" devices 2>/dev/null | awk 'NR > 1 && $2 == "device" && $1 ~ /^emulator-/ { print $1 }'); do
    if [ "$(avd_of "$s")" = "$AVD" ]; then printf '%s' "$s"; return 0; fi
  done
  return 0
}
booted() { [ "$("$ADB" -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }
# 拉起的模拟器还活着吗：launcher 可能 exec 成 qemu（同一 PID），也可能另起子进程——两样都看，
# 只按 PID 判会把正常开机误判成「提前退出」。-avd 后面锚定空格或行尾，免得 a35 匹配到 a35b
emu_alive() { kill -0 "$1" 2>/dev/null || pgrep -f -- "-avd ${AVD}( |\$)" >/dev/null 2>&1; }

device_run() {   # device_run <apk> <截图目录>
  local apk="$1" out="$2"
  local emu_log
  emu_log="$(dirname "$out")/emulator.log"
  [ -s "$apk" ] || fail "没有收到 APK（${apk}）"
  device_preflight

  local serial emu_pid=""
  if [ -n "$SERIAL" ]; then
    # 1'. 指定了设备：preflight 已确认在线。实体机灭屏或锁着时 am start 照样 ok，截图却是黑屏 / 锁屏——
    # 先亮屏、解无密码锁屏；有密码的解不开，截图里会看到锁屏（所以退出码 0 后仍要看图）
    serial="$SERIAL"
    booted "$serial" || unavailable "${serial} 还没开完机"
    "$ADB" -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    "$ADB" -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    log "设备：${serial}（$("$ADB" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')）"
  else
    # 1. 找在线的目标 AVD；不在就后台拉起并等开机
    "$ADB" start-server >/dev/null 2>&1 || true
    serial="$(find_serial)"
    if [ -z "$serial" ]; then
      # 有图形会话用 AGENTS.md 的常规命令；经 ssh 进来没有显示时换成它写明的无窗口形态，否则 emulator 起不来
      if [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
        set -- -avd "$AVD" -no-snapshot -gpu auto
      else
        set -- -avd "$AVD" -no-snapshot -no-window -no-audio -gpu swiftshader_indirect
      fi
      log "${AVD} 不在线，后台拉起：emulator $*"
      # stdin 也要切断：留着 ssh 的通道，会话结束时它会被带走，ssh 也可能一直等它
      nohup "$EMULATOR" "$@" < /dev/null > "$emu_log" 2>&1 &
      emu_pid=$!
    fi

    local deadline=$(( $(date +%s) + BOOT_LIMIT ))
    until [ -n "$serial" ] && booted "$serial"; do
      if [ -n "$emu_pid" ] && ! emu_alive "$emu_pid"; then
        unavailable "emulator 进程提前退出（日志：$(uname -n):${emu_log}）"
      fi
      if [ "$(date +%s)" -ge "$deadline" ]; then
        unavailable "${BOOT_LIMIT} 秒内 ${AVD} 没有开完机"
      fi
      sleep 2
      [ -n "$serial" ] || serial="$(find_serial)"
    done
    log "设备：${serial}（${AVD}）"
  fi

  # 2. 安装、清 logcat、启动、确认活着且没崩
  local install_out start_out crash
  if ! install_out="$("$ADB" -s "$serial" install -r "$apk" 2>&1 | tr -d '\r')"; then
    printf '%s\n' "$install_out" >&2
    case "$install_out" in
      # 包在本机构建、装在远端：两台机器的 debug key 不是同一把时，覆盖安装一律被拒（AGENTS.md「Dev / build / run」）
      *INSTALL_FAILED_UPDATE_INCOMPATIBLE*) fail "adb install -r 失败：设备上已装的 ${PKG} 是另一把 debug key 签的——构建机的 ~/.android/debug.keystore 要和 keystone 的是同一把" ;;
      *) fail "adb install -r 失败" ;;
    esac
  fi
  printf '%s\n' "$install_out" >&2
  "$ADB" -s "$serial" logcat -b all -c >/dev/null 2>&1 || log "清 logcat 失败（继续，crash 判断可能带上旧记录）"
  start_out="$("$ADB" -s "$serial" shell am start -W -n "$ACTIVITY" 2>&1 | tr -d '\r')"
  printf '%s\n' "$start_out" >&2
  grep -q '^Status: ok' <<<"$start_out" || fail "am start 没有报 Status: ok"
  sleep "$SETTLE"
  crash="$("$ADB" -s "$serial" logcat -b crash -d 2>/dev/null | tr -d '\r')"
  if grep -q 'FATAL EXCEPTION' <<<"$crash" && grep -q "Process: ${PKG}," <<<"$crash"; then
    grep -A20 'FATAL EXCEPTION' <<<"$crash" >&2
    fail "crash buffer 里有 ${PKG} 的 FATAL EXCEPTION"
  fi
  [ -n "$("$ADB" -s "$serial" shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ] || fail "启动 ${SETTLE} 秒后 ${PKG} 的进程不在了"

  # 3. 截图
  local shot
  shot="$out/smoke-$(date +%Y%m%d-%H%M%S).png"
  "$ADB" -s "$serial" exec-out screencap -p > "$shot" 2>/dev/null || fail "screencap 失败"
  # PNG 文件头 89 50 4E 47：截到的不是图（设备返回了报错文本）就算失败
  [ "$(head -c 4 "$shot" | od -An -tx1 | tr -d ' \n')" = "89504e47" ] || fail "截到的不是 PNG：${shot}"
  printf '%s\n' "$shot"
  exit 0
}

case "${1:-}" in
  --device-check) device_preflight; exit 0 ;;
  --device) [ $# -eq 3 ] || fail "用法：$0 --device <apk> <截图目录>"; device_run "$2" "$3" ;;
  "") ;;
  *) fail "不认识的参数：$1（本机直接跑、不带参数；--device* 只给 ssh 调）" ;;
esac

# ---------------------------------------------------------------------------------------------------
# 本机侧：构建、送到 $HOST、取回截图
# ---------------------------------------------------------------------------------------------------
# 这些值会拼进远端命令行，只收不需要引号的字符；主机名不许以 - 开头，免得被 ssh 当成选项
case "$HOST" in ''|-*|*[!A-Za-z0-9._@-]*) fail "PETREL_SMOKE_HOST 取值不合法：${HOST}" ;; esac
case "$AVD" in ''|*[!A-Za-z0-9._-]*) fail "PETREL_SMOKE_AVD 取值不合法：${AVD}" ;; esac
# 可以为空；网络连接的设备 serial 形如 192.168.2.7:5555，所以放行冒号
case "$SERIAL" in -*|*[!A-Za-z0-9._:-]*) fail "PETREL_SMOKE_SERIAL 取值不合法：${SERIAL}" ;; esac
case "$BOOT_LIMIT" in ''|*[!0-9]*) fail "PETREL_SMOKE_BOOT 必须是整数秒：${BOOT_LIMIT}" ;; esac
case "$SETTLE" in ''|*[!0-9]*) fail "PETREL_SMOKE_SETTLE 必须是整数秒：${SETTLE}" ;; esac

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
SELF="$SELF_DIR/$(basename "$0")"
ROOT="$(cd "$SELF_DIR/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
SMOKE_DIR="$ROOT/app/build/smoke"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4)
REMOTE_ENV="PETREL_SMOKE_SERIAL=${SERIAL} PETREL_SMOKE_AVD=${AVD} PETREL_SMOKE_BOOT=${BOOT_LIMIT} PETREL_SMOKE_SETTLE=${SETTLE}"

# rsh <远端命令>：stdin 接 /dev/null（远端即使跑到读 stdin 的命令也读不到本机的输入）；
# rput <本机文件> <远端路径>：文件内容走 stdin。ssh 自己出错时退出码是 255，远端的本脚本从不返回 255，
# 所以 255 一律按「连不上 / 断线」算设备不可用。在 $(…) 里调用时 exit 只退子 shell，调用处要接 `|| exit $?`。
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
cleanup() {   # 删远端这次的临时目录；删不掉不影响退出码
  if [ -n "$RUN" ]; then ssh -n "${SSH_OPTS[@]}" "$HOST" "rm -rf '$RUN'" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# 1. 连远端、建这次的临时目录、送脚本过去，先看远端有没有设备——没有就别先花时间构建
log "连接 ${HOST}"
RUN="$(rsh 'mkdir -p "$HOME/.cache/petrel-smoke" && mktemp -d "$HOME/.cache/petrel-smoke/run.XXXXXX"')" || exit $?
case "$RUN" in
  /*/.cache/petrel-smoke/run.*) case "$RUN" in *[!A-Za-z0-9._/-]*) RUN=""; fail "远端临时目录路径含意外字符" ;; esac ;;
  *) bad="$RUN"; RUN=""; fail "远端没有建出临时目录（得到：${bad}）" ;;
esac
rput "$SELF" "$RUN/device-smoke.sh" || fail "脚本送不到 ${HOST}:${RUN}"
rsh "${REMOTE_ENV} bash '$RUN/device-smoke.sh' --device-check" || exit $?

# 2. 本机构建当前的树（mac 上按 AGENTS.md 去掉代理变量，让 gradle 直连 Aliyun 镜像）
log "构建 :app:assembleDebug（${ROOT}）"
( cd "$ROOT" && unset http_proxy https_proxy all_proxy && ./gradlew :app:assembleDebug -q ) >&2 || fail "构建失败"
[ -s "$APK" ] || fail "构建完了却没有 ${APK}"

# 3. 送 APK，远端装机、启动、查崩溃、截图
log "送 APK 到 ${HOST}"
rput "$APK" "$RUN/app-debug.apk" || fail "APK 送不到 ${HOST}:${RUN}"
remote_shot="$(rsh "${REMOTE_ENV} bash '$RUN/device-smoke.sh' --device '$RUN/app-debug.apk' '$RUN'")" || exit $?
case "$remote_shot" in
  "$RUN"/smoke-*.png) ;;
  *) fail "远端没有给出截图路径（得到：${remote_shot}）" ;;
esac

# 4. 截图取回本机（在 build 目录下，不进 git）
mkdir -p "$SMOKE_DIR"
shot="$SMOKE_DIR/$(basename "$remote_shot")"
rsh "cat '$remote_shot'" > "$shot" || fail "取回截图失败：${HOST}:${remote_shot}"
[ "$(head -c 4 "$shot" | od -An -tx1 | tr -d ' \n')" = "89504e47" ] || fail "取回的不是 PNG：${shot}"
log "✓ 通过；截图 ${shot}"
printf '%s\n' "$shot"
exit 0
