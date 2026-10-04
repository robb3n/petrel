#!/usr/bin/env bash
# chain-check.sh — 在手机上验证Petrel的代理链路，供原型验收与后续回归。
#   1. mihomo REST（经 adb forward）测 PROXY 组里每个节点、以及 ts 节点到 tailnet 探针的延迟；
#   2. 手机 shell 发真实请求：generate_204、出口 IP、经 tailnet 访问探针；
#   3. 列出 /connections 里的链路；--switch 时把 PROXY 依次切到每个节点看出口 IP，最后切回原选中项。
#
# 用法：scripts/chain-check.sh <config.yaml> [--switch]
#   从本机配置读 external-controller 的 secret（不打印）；探针默认取配置 hosts 里 hu 的地址 + Navidrome ping。
#   配置里必须显式写 `secret:`：没写时 App 会注入一个随机 secret（脚本拿不到），带空 Bearer 调 REST 只会全部 401，
#   所以读不到 secret 就直接报错退出（退出码 2）。
# 环境变量：PETREL_HOST（默认 keystone）、PETREL_SERIAL（默认 b65040ad）、PETREL_TS_PROBE（探针 URL）
# 退出码：0 全部通过；1 有检查失败（逐项看输出）；2 配置没写 secret
set -euo pipefail

HOST="${PETREL_HOST:-keystone}"
SERIAL="${PETREL_SERIAL:-b65040ad}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10)
cfg="${1:?用法：$0 <config.yaml> [--switch]}"
SWITCH="${2:-}"
case "$HOST" in ''|-*|*[!A-Za-z0-9._@-]*) echo "chain-check: PETREL_HOST 不合法" >&2; exit 1 ;; esac
case "$SERIAL" in ''|-*|*[!A-Za-z0-9._:-]*) echo "chain-check: PETREL_SERIAL 不合法" >&2; exit 1 ;; esac

secret="$(awk '/^secret:/ { sub(/^secret:[ ]*/, ""); gsub(/["'\'']/, ""); print; exit }' "$cfg")"
if [ -z "$secret" ]; then
  echo "chain-check: 配置没写 secret，chain-check 需要显式 secret（没写时 App 注入随机 secret，脚本无从得知）" >&2
  exit 2
fi
hu_ip="$(awk '/^[ ]+hu:[ ]/ { print $2; exit }' "$cfg")"
probe="${PETREL_TS_PROBE:-http://${hu_ip:-100.64.0.1}:4533/rest/ping.view}"

RUN="$(ssh -n "${SSH_OPTS[@]}" "$HOST" 'umask 077 && mkdir -p "$HOME/.cache/petrel" && mktemp -d "$HOME/.cache/petrel/chain.XXXXXX"')"
case "$RUN" in /*/.cache/petrel/chain.*) ;; *) echo "chain-check: 远端临时目录异常：${RUN}" >&2; exit 1 ;; esac
trap 'ssh -n "${SSH_OPTS[@]}" "$HOST" "rm -rf '"'"'$RUN'"'"'" >/dev/null 2>&1 || true' EXIT

# secret 写进远端 600 文件而不是命令行参数，免得出现在远端进程列表里
printf '%s' "$secret" | ssh "${SSH_OPTS[@]}" "$HOST" "cat > '$RUN/secret'"
ssh "${SSH_OPTS[@]}" "$HOST" "cat > '$RUN/remote.sh'" <<EOS
set -u
A="\$HOME/Android/Sdk/platform-tools/adb"; S="$SERIAL"; PORT=19090; FAIL=0
SECRET="\$(cat '$RUN/secret')"
api() { curl -s -m 20 -H "Authorization: Bearer \$SECRET" "\$@"; }
enc() { python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "\$1"; }
phone() { "\$A" -s "\$S" shell "\$1" </dev/null | tr -d '\r'; }
check() { if [ "\$2" = ok ]; then echo "  ✓ \$1"; else echo "  ✗ \$1"; FAIL=1; fi; }

"\$A" -s "\$S" forward tcp:\$PORT tcp:9090 </dev/null >/dev/null
B="http://127.0.0.1:\$PORT"
group="\$(api "\$B/proxies/PROXY")"
now="\$(printf '%s' "\$group" | jq -r .now)"
mapfile -t members < <(printf '%s' "\$group" | jq -r '.all[]')
echo "PROXY 当前：\$now；成员：\${members[*]}"

echo "[1] mihomo 节点延迟"
for p in "\${members[@]}"; do
  r="\$(api "\$B/proxies/\$(enc "\$p")/delay?url=\$(enc https://www.gstatic.com/generate_204)&timeout=8000")"
  d="\$(printf '%s' "\$r" | jq -r '.delay // empty')"
  [ -n "\$d" ] && check "\$p：\${d} ms" ok || check "\$p：\$r" no
done
r="\$(api "\$B/proxies/ts/delay?url=\$(enc "${probe}")&timeout=8000")"
d="\$(printf '%s' "\$r" | jq -r '.delay // empty')"
[ -n "\$d" ] && check "ts → ${probe}：\${d} ms" ok || check "ts → ${probe}：\$r" no

echo "[2] 手机端到端（经 TUN）"
code="\$(phone "curl -s -m 10 -o /dev/null -w '%{http_code}' https://www.google.com/generate_204")"
[ "\$code" = 204 ] && check "google generate_204 → \$code" ok || check "google generate_204 → \${code:-无响应}" no
ip="\$(phone "curl -s -m 10 https://ifconfig.co/ip")"
[ -n "\$ip" ] && check "出口 IP：\$ip（PROXY=\$now）" ok || check "出口 IP 取不到" no
code="\$(phone "curl -s -m 10 -o /dev/null -w '%{http_code}' ${probe}")"
[ "\$code" = 200 ] && check "tailnet 探针 → \$code" ok || check "tailnet 探针 → \${code:-无响应}" no

echo "[3] /connections 里的链路（去重）"
api "\$B/connections" | jq -r '.connections[]? | "\(.metadata.host // .metadata.destinationIP) ← \(.chains | reverse | join(" → ")) [\(.rule)]"' | sort -u | head -12 | sed 's/^/  /'

if [ "${SWITCH}" = "--switch" ]; then
  echo "[4] 切换 PROXY 看出口"
  for p in "\${members[@]}"; do
    api -X PUT -H 'Content-Type: application/json' -d "{\"name\":\"\$p\"}" "\$B/proxies/PROXY" >/dev/null
    sleep 1
    ip="\$(phone "curl -s -m 10 https://ifconfig.co/ip")"
    [ -n "\$ip" ] && check "PROXY=\$p → 出口 \$ip" ok || check "PROXY=\$p → 出口取不到" no
  done
  api -X PUT -H 'Content-Type: application/json' -d "{\"name\":\"\$now\"}" "\$B/proxies/PROXY" >/dev/null
  echo "  已切回 \$now"
fi

"\$A" -s "\$S" forward --remove tcp:\$PORT </dev/null >/dev/null 2>&1 || true
exit \$FAIL
EOS
ssh -n "${SSH_OPTS[@]}" "$HOST" "bash '$RUN/remote.sh'"
