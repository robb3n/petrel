# Lessons — 切网恢复：回调要跟踪 NOT_VPN 网络，tsnet 要靠我们唤醒

来源：首个原型（`av:dllg404i`）在一加 12（ColorOS 16 / Android 16）上的 Wi-Fi ↔ 蜂窝切换实测。

## 结果

两轮 Wi-Fi → 蜂窝 → Wi-Fi，全程不重启服务：

- tailnet 不中断：每次切换后经 tailnet 访问 hu 的探针都立即返回 200。
- 第一次切到蜂窝时代理约 9 秒恢复（三次测量一致，应该是首次在蜂窝上探测到 tc-u-se 的路径）；之后的切换都立即恢复。
- v1 四条任务做完后（`cab8da1`）又测了一次：`cmd wifi set-wifi-enabled disabled|enabled`，每次切换后等 12–15 秒跑 `scripts/chain-check.sh`，两个方向全部通过。

## tailscale 的 netmon 在 Android 上 10 分钟才轮询一次

`net/netmon/polling.go`：Android 上轮询间隔是 10 分钟，注释写明「要靠 Android 主动通知 link monitor」。不通知的话，切网后 tsnet 要很久才发现。sing-box 同类功能「切网后不恢复」的问题，很可能就是这个原因。

通知方式：`tsnet.Server.Sys().NetMon.GetOK()` 拿到 monitor，调用 `InjectEvent()`。这是 `ptcore.NotifyNetworkChanged` 去抖 1 秒之后做的事。日志里会看到 `network changed: tailnet link monitor notified`，随后 tsnet 关掉绑在旧网络上的连接（`closing system connection … due to link change`）。

不能用 `LocalClient.DebugAction("rebind")`：metacubex/tailscale 裁掉了 localapi 的 debug 处理，调用会失败。

## `registerDefaultNetworkCallback` 跟踪到的是自己的 VPN

在 Android 16 / ColorOS 上，VPN App 调用 `registerDefaultNetworkCallback`，跟踪到的「默认网络」是它自己的 VPN（`dumpsys connectivity` 里本 uid 的默认请求由 `ni{VPN … VPN:com.robb3n.petrel}` 满足），即使本 App 已经被排除在 VPN 外。VPN 网络在切 Wi-Fi 时不变，所以回调只在注册时触发一次，之后再也不触发。

修法：显式请求 `NET_CAPABILITY_INTERNET + NET_CAPABILITY_NOT_VPN`。API 31 及以上用 `registerBestMatchingNetworkCallback`，29–30 用 `registerNetworkCallback`。

## 死锁：持有 `mu` 时不得调用 tsnet / mihomo / Host

修上面那个问题时还发现一个死锁：

- `watchTailnet` 持有 `mu` 时调用了 `s.TailscaleIPs()`，这要拿 tailscale 的内部锁。
- tailscale 持有内部锁时会回调我们的 `Logf`，旧版 `logf` 要拿 `mu`。两边互等，卡死。
- 卡死之后，网络回调调进 `NotifyNetworkChanged` 就再也不返回，整个进程的 ConnectivityThread 被堵住；tailscale 的协程也卡在 Logf 上，切网后 tailnet 不再恢复。mihomo 的数据面不用 `mu`，所以流量看起来还在跑，很难发现。

现在的规则（写在 `core/ptcore/core.go` 的变量注释里）：

- `mu` 只保护几个状态变量，持有它时不调用任何外部代码。
- `host` 改成无锁读取（`atomic.Pointer`）。
- Kotlin 回调先把调用投递到工作线程，再调 Go，不在系统回调线程里同步调用。`onDestroy` 同理（在主线程调 `Ptcore.stop()` 有 ANR 风险）。规则见 AGENTS「代码约定 / 经验」。
- `Start` / `Stop` 的串行锁 `lifecycleMu` 与 `mu` 是两把锁：`lifecycleMu` 整段持有、期间可以调 tsnet / mihomo；拿了 `lifecycleMu` 才可以拿 `mu`，反过来不行。解析配置另有 `parseMu`（见 `config-import.md`），也不与 `mu` 嵌套。

回归测试：`TestTsnetLogfDoesNotTakeMu`。

## 首轮测速：tailnet 到 Running，不代表到对端的路已经通

- 症状：每次开 VPN 后的自动测速，延迟比正常高两秒多；过一会手动再测就正常。2026-10-04 在真机上冷启动（Wi-Fi）测得：自动测速 2922 / 2385 ms，测完立刻再测 1062 / 374 ms，之后稳定在这个量级。
- 时间线（logcat），见到过两种：
  - 慢在 DERP 连接上：VPN 起来约 3 秒后 tailnet 才到 Running，自动测速在同一毫秒开始。此时 tsnet 刚开始连对端前置节点的 home DERP（东京），连上用了 2.1 秒。WireGuard 的首个握手包在 disco 找到直连之前就发了，只能走这个还没连上的 DERP，于是一直排队。2.1 秒加上正常延迟，正好是首轮的数字。disco 50 ms 就找到了直连，所以大头不是走中继，而是首个握手包被 DERP 连接卡住。
  - 慢在握手重发上：tsnet 拿缓存的状态在启动后立刻报 Running，但控制面的新 netmap 两秒后才到，又过半秒才找到去对端的路径。首个握手包丢了，wireguard-go 要等固定的 5 秒才重发；在这之前发的包都排在这个握手后面。只拨一次、超时 5 秒的预热正好错过（实测 `0/1 connected in 5001 ms`，首轮仍是 2027 / 1356 ms）。
  - 刚换到直连时整体偏慢：预热已经连上，tailscale 也在 0.5 秒前报了直连，可经 tailnet 的节点首轮全部多出约 1.3 秒；不经 tailnet 的节点不受影响，几秒后再测就正常。单次 TCP 拨号的耗时从连上那一刻起就只有 40 到 90 ms，看不出这段异常。原因没有坐实，推测是对端回程还在中继。
- 做法：自动测速前先跑 `warmTailnetHops`。它经 `ts` 向每个以 `ts` 为 `dialer-proxy` 的节点的服务器地址反复拨 TCP，单次最多 2 秒，总上限 10 秒（要盖过 5 秒的握手重发）；连上之后再每 300 ms 拨一次、持续 3 秒（稳定期，保持有流量，tailscale 只在有流量时做路径升级），然后才测。
- 实测（2026-10-04，一加 12，Wi-Fi）：只有前一段反复拨号时，5 次冷启动里 1 次首轮仍偏高；加了稳定期后，加上临时埋点那 4 次，共 7 次冷启动的首轮都和紧接着再测的数字同一量级（例如 lax 890 到 1126 ms、tokyo 380 到 561 ms）。预热总耗时 4 到 9 秒。手动测速不预热，它通常发生在通路热了之后。
- 切网后同样先预热，再强制探测各组当前选中的节点（`probeAfterNetworkChange`）；界面可见时每 30 秒探测一次当前选中的节点（`ProbeSelected`，25 秒内测过的跳过），后台不探测。
- 判断时机时不要只看 tailnet 的 Running：它可能来自缓存，那时还没有新 netmap，到对端的路径更没建好。
- 同一窗口的副作用：VPN 已起、tailnet 还没 Running 的那 3 秒里，应用发起的走 `PROXY` 的连接会一直等到 mihomo 拨号超时（日志里是 `connect error: … context deadline exceeded`），不会在 tailnet 通了之后自动接上。

## 怎么验证切网

ColorOS 的 logcat 主缓冲区只有 256 KiB，系统日志又很多，几十秒前的内容就会被覆盖，事后 dump 抓不到切网那几秒。办法有两个：

- 切网时实时抓流：`adb logcat -T 1 -v time Petrel:V '*:S' > file &`。注意同一个 tag 写多次时只有最后一个生效，`-s Petrel:I Petrel:W` 实际只剩 W。
- 每次底层网络回调都会打一行 `underlying network …`，经 `PLog` 落进 `files/logs/petrel.log`，用 `run-as com.robb3n.petrel cat files/logs/petrel.log` 读（release 包用设置里的「导出日志」）。

切网命令是 `adb shell cmd wifi set-wifi-enabled disabled|enabled`。蜂窝数据要开着。测完必须恢复 Wi-Fi。

## 节点名：首次注册时生成，之后不再改

节点名原来写死成维护者手机的 `op12-petrel`，公开发布后每个用户的设备都会叫这个名字。现在由 Kotlin 侧的 `TailnetHostname` 决定，经 `Ptcore.start` 的参数传给 tsnet：

- 首次注册时用机型生成：`Build.MODEL` 转小写，非字母数字换成 `-`，截到 40 个字符，再加 `-petrel`，例如一加 12 是 `pjd110-petrel`。名字写进 `files/tailnet-hostname`，之后一直沿用，换系统版本或改了机型字串也不变。
- 老安装没有这个文件、但 `files/tsnet/` 里已有节点状态（节点名还写死在代码里时注册的）时，写入并沿用 `op12-petrel`，已注册节点的名字不会因为升级而变。
- 卸载会连同 `files/` 一起删掉，重装后按新机器注册，名字重新生成。
- 文件先写临时文件再改名：写到一半被杀不会留下空文件，否则空文件加上已有的 tsnet 状态会被当成老安装。写不进去（磁盘满）也不报错，这一次照样用算出来的名字，下次启动再写。已知的边角：新装机器首次注册时恰好写失败，下次启动会被当成老安装，名字退回 `op12-petrel`。概率极低，没有另加防护。
- Go 侧没有节点名常量：节点列表里 Self 没带名字时用 `tsnet.Server.Hostname` 兜底；空名字直接让 `Start` 失败。
- tsnet 的两路日志（`Logf` 里控制面客户端的 `AuthURL is <链接>`、`UserLogf` 里的 `… or go to: <链接>`）都会打出完整登录链接，一律经 `redactLoginURLs` 换成 `<login URL>`。完整链接只许出现在 `watchTailnet` 打的 `tailnet login URL:` 那一行。

