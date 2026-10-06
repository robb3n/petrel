# Lessons：连接模式与出口 IP

来源：做连接模式和出口 IP 的那次 inline 工作（2026-10-06），在一加 12（ColorOS 16）上实测。规格见 `docs/spec/conn-mode.md`。

## 仅 tailnet：分流 VPN 也要给 IPv6 地址

- 只 `addRoute` tailnet 的两个网段（`100.64.0.0/10`、`fd7a:115c:a1e0::/48`），不加 `addDnsServer`，其它流量和 DNS 就都走底层网络。手机上 `ip route show table all` 里，tun0 的路由表只有这两段（另有 tun 自己的 /30 与 /126）。
- IPv6 地址必须加。VpnService 对没有地址的地址族会整族拦截（见 `Builder.allowFamily` 的文档）。只给 IPv4 地址的话，开着「仅 tailnet」时手机上所有 IPv6 都会断。
- 内核那边不读用户配置，用的是内置的 `mode: rule` + `MATCH,ts`。进到 TUN 的只有发往 tailnet 的流量，全部交给 `ts` 即可。`TestTailnetOnlyConfigParses` 固定了这份配置能被 mihomo 解析。
- 实测：手机 shell 经 tailnet 访问 hu 的探针返回 200；`ifconfig.co` 看到的是手机自己网络的出口，不经代理。

## 仅代理：`ts` 换成占位，不能直接删

- 用户配置里写着 `dialer-proxy: ts`，不注入 `ts` 的话 mihomo 解析不过。占位节点指向 `127.0.0.1:1`（校验时用的就是这个地址），经它的节点会被立即拒绝，不用等超时。
- 自动测速原来要等 tailnet Running 才触发，仅代理永远等不到；现在仅代理模式下 VPN 一起来就测。`warmTailnetHops` 在仅代理模式下直接返回，否则会对占位地址反复拨号，空转 10 秒。
- `running()` 原来靠 `srv != nil` 判断内核在不在，仅代理没有 `srv`，所以改成单独的 `active` 标志。凡是要判断内核在不在的地方，都别再看 `srv`。

## 出口 IP：VPN 刚起来时的第一次查询会失败

- 现象：开 VPN 后连接页显示「查不到出口 IP」，点一下刷新就好了。实时抓日志看到 `exit ip query failed: proxyerror`，时间点正是 tailnet 刚到 Running 的那一刻。这时经 `ts` 的通路还没预热好（内核正在跑 `warmTailnetHops`，最多 10 秒，见 `network-change.md`「首轮测速」），拨号一秒内就失败了。
- 做法：当前出口的查询失败后，按 3 秒、5 秒、8 秒的间隔重试，都失败才显示「查不到」。每次重试前都核对出口有没有变过（`token`），变了就作废这一轮。
- gomobile 抛给 Kotlin 的 Go 错误，类名是 `go.Universe$proxyerror`，`javaClass.simpleName` 得到的是 `proxyerror`。日志只记这个类名：Go 的错误信息可能带节点名和服务器地址。
- logcat 缓冲区很快会被覆盖，事后 dump 往往已经找不到失败那一行，要像切网那样实时抓流（`logcat -T 1 Petrel:V '*:S'`）。

## 出口 IP：Go 侧经节点查，仅 tailnet 由 Kotlin 直接查

- 经节点查：`proxydialer.New(node)` 拨号，与 mihomo 处理 `dialer-proxy` 走同一条路径，域名交给节点的远端解析，所以不受本机 DNS 影响，也不用切换当前选中的节点。
- 仅 tailnet 时，要查的是手机自己的出口。本 App 进程被排除在 VPN 之外，直接用 `HttpURLConnection` 查即可。不用 Go 的 `net.Dialer`：Android 上没有 `/etc/resolv.conf`，Go 自带的解析器靠不住。ip-api 的免费接口只有 http，`network_security_config` 只对 ip-api.com 放行明文。

## 真机调试小坑

- 经 ssh 在 keystone 上跑 adb，远端是 zsh：`A="adb -s X"; $A …` 不会按空格拆开，会报 `command not found: adb -s X`。辅助命令写成 bash 脚本送过去执行（同 `config-import.md`）。
- 按 uiautomator 的 `text` 找控件来点时，自绘开关和它所在行的标题可能同名（比如设置页的「代理」），先匹配到的是不可点的标题。这种控件按截图里的坐标点。
- 刚切换标签时立刻点按钮，点击可能被 pager 的滑动吃掉。等一两秒再点。
