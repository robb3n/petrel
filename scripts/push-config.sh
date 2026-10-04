#!/usr/bin/env bash
# push-config.sh — 把本机的 mihomo 配置（和可选的 GeoIP 库）写进手机上Petrel debug 包的私有目录。
# 用法：scripts/push-config.sh <config.yaml> [Country.mmdb]
#
# 经 PETREL_HOST（默认 keystone）用它自己的 adb 执行，做法同 device-smoke.sh。配置含节点凭据：
# 只在本机、远端临时目录（umask 077，跑完删）和设备的 App 私有目录出现；内容经 adb shell 的 stdin
# 直接写进 run-as 目录，设备上不落 /data/local/tmp 临时文件。需要已装 debug 包（run-as 只对 debuggable 包有效）。
# 写完 config.yaml 会顺手删掉 files/config.meta.json（界面导入时记的原文件名与时间），免得界面还显示上一次导入的名字；
# 之后配置页显示 config.yaml 与文件修改时间（「…更新」），没有「校验通过」。
#
# 环境变量：PETREL_HOST（默认 keystone）、PETREL_SERIAL（默认 b65040ad，一加 12）
set -euo pipefail

HOST="${PETREL_HOST:-keystone}"
SERIAL="${PETREL_SERIAL:-b65040ad}"
PKG="com.robb3n.petrel"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10)

cfg="${1:?用法：$0 <config.yaml> [Country.mmdb]}"
mmdb="${2:-}"
[ -s "$cfg" ] || { echo "push-config: 找不到 ${cfg}" >&2; exit 1; }
[ -z "$mmdb" ] || [ -s "$mmdb" ] || { echo "push-config: 找不到 ${mmdb}" >&2; exit 1; }
case "$HOST" in ''|-*|*[!A-Za-z0-9._@-]*) echo "push-config: PETREL_HOST 不合法" >&2; exit 1 ;; esac
case "$SERIAL" in ''|-*|*[!A-Za-z0-9._:-]*) echo "push-config: PETREL_SERIAL 不合法" >&2; exit 1 ;; esac

RUN="$(ssh -n "${SSH_OPTS[@]}" "$HOST" 'umask 077 && mkdir -p "$HOME/.cache/petrel" && mktemp -d "$HOME/.cache/petrel/push.XXXXXX"')"
case "$RUN" in /*/.cache/petrel/push.*) ;; *) echo "push-config: 远端临时目录异常：${RUN}" >&2; exit 1 ;; esac
trap 'ssh -n "${SSH_OPTS[@]}" "$HOST" "rm -rf '"'"'$RUN'"'"'" >/dev/null 2>&1 || true' EXIT

ssh "${SSH_OPTS[@]}" "$HOST" "cat > '$RUN/config.yaml'" < "$cfg"
[ -z "$mmdb" ] || ssh "${SSH_OPTS[@]}" "$HOST" "cat > '$RUN/Country.mmdb'" < "$mmdb"

# 设备侧脚本写成远端文件再执行：adb 会吞 stdin，不能走 heredoc 喂给远端 bash（Mu3ic docs/lessons/remote-adb-ssh.md）
ssh "${SSH_OPTS[@]}" "$HOST" "cat > '$RUN/remote.sh'" <<EOS
set -eu
A="\$HOME/Android/Sdk/platform-tools/adb"
S="$SERIAL"
"\$A" -s "\$S" shell "run-as $PKG mkdir -p files/mihomo" </dev/null
"\$A" -s "\$S" shell "run-as $PKG sh -c 'cat > files/config.yaml'" < "$RUN/config.yaml"
if [ -f "$RUN/Country.mmdb" ]; then
  "\$A" -s "\$S" shell "run-as $PKG sh -c 'cat > files/mihomo/Country.mmdb'" < "$RUN/Country.mmdb"
fi
"\$A" -s "\$S" shell "run-as $PKG chmod 600 files/config.yaml" </dev/null
"\$A" -s "\$S" shell "run-as $PKG rm -f files/config.meta.json" </dev/null
"\$A" -s "\$S" shell "run-as $PKG ls -l files/config.yaml files/mihomo" </dev/null
EOS
ssh -n "${SSH_OPTS[@]}" "$HOST" "bash '$RUN/remote.sh'"
echo "push-config: ✓ 已写入 ${SERIAL} 上 ${PKG} 的 files/config.yaml" >&2
