# Lessons — VPN 防自环、tun stack 与 mihomo 的 Android 构建标签

来源：首个原型（`av:dllg404i`）在一加 12（ColorOS 16 / Android 16）上的实测。

## 防自环：把本 App 排除出 VPN，tun stack 只能用 gvisor

- `PetrelVpnService` 用 `addDisallowedApplication(packageName)` 防自环：本进程里 mihomo 的出站、tsnet 的 WireGuard / DERP / 控制面连接全部走底层网络，不需要逐个 `protect()`。实测链路正常，`/connections` 里没有回环。
- 代价是 **tun stack 只能用 gvisor**。system / mixed stack 靠内核 NAT：sing-tun 在 tun 地址上开监听，把包改写后回注 tun，回包从本进程的 socket 发出。本进程被排除出 VPN 后，回包按底层网络路由，回不到 tun0，链路会断。gvisor 全在用户态，不依赖内核路由。
- 顺带：mihomo 的 tun 地址取自 `dns.fake-ip-range`（默认 `198.18.0.1/30`），与 VpnService 的 `172.19.0.1/30` 不一致。gvisor 不在乎这一点，system stack 会因为地址对不上而 bind 失败。
- 想改回 system stack，就必须同时改用 `protect()` 防自环（mihomo 走 `dialer.DefaultSocketHook`，tsnet 走 `netns.SetAndroidProtectFunc`），并让 VpnService 的地址对齐 fake-ip-range。

## mihomo 必须带 `cmfa` 构建标签，否则 TUN 建不起来

- 症状：VPN 已建立、tailnet 是 Running、mihomo REST 也正常，但手机上所有流量都超时，`/connections` 为空。`GET /configs` 显示 `tun.enable: false`：mihomo 建 TUN 失败时只打一条日志，并把 enable 复位，`hub.ApplyConfig` 不返回错误。
- 真正的错误：`Start TUN listening error: build android rules: read packages list: open /data/system/packages.xml: permission denied`。`listener/sing_tun/server_android.go`（`android && !cmfa`）要读 `packages.xml` 来建包名规则，只有特权进程能读。
- 修法：照 CMFA 的做法，用 `with_gvisor,cmfa` 编（见 `core/build.sh`）。`cmfa` 带来的其余变化：
  - 关掉进程名查找和回环检测。
  - REST 进入 embed 模式：禁止 `PATCH /configs`、restart、upgrade；`PUT /proxies/<组>` 切节点不受影响。
  - `system` DNS 改由宿主调用 `dns.UpdateSystemDNS` 提供。测试配置写死了 nameserver，所以不受影响；以后要支持 `system` 时得从 Android 侧喂。
- `ptcore.Start` 现在会在 `ApplyConfig` 之后检查 `listener.GetTunConf().Enable`：TUN 没起来就当作启动失败上报，不再显示「已连接」。

## TUN fd 的所有权

`Ptcore.start` 收到 fd 之后，Kotlin 侧就不再碰它：交给 mihomo 之前失败，由 Go 关掉；交出去之后由 Go 与 mihomo 负责。Kotlin 再关一次的话，如果这个编号已被复用，就会误关别的文件。

TUN 没建起来（`listener.GetTunConf().Enable == false`）时，**mihomo 不会关这个 fd**，不处理的话 VPN 接口一直挂着、流量全被吞掉。读 mihomo v1.19.32 与 sing-tun v0.4.27 的 `file-descriptor` 路径后的结论：

- `sing_tun.New` 在 fd ≠ 0 时，`tun.New` 只是 `os.NewFile(fd)` 包一下，本身不会失败。失败发生在它前后：
  - 之前：`buildAndroidRules`（读 `packages.xml`，缺 `cmfa` 标签时就是这里失败）、auto-redirect 初始化等。fd 还没被包装，没有任何人去关。
  - 之后：`NewStack` / `Start` 失败。fd 已被 `os.File` 包住，但 `ReCreateTun` 丢弃返回的 listener、不调 `Close`，只剩 `os.File` 的 GC finalizer 迟早去关。
- 所以 Go 在失败时直接 `close(fd)` 不安全：第二类路径上，finalizer 日后会再关一次这个编号，Android 会复用编号，可能误关别的文件（比如 tsnet 的 socket）。
- 也不能先 dup 一份交给 mihomo：dup 出来的那个编号有同样的问题。
- 现在的做法（`releaseTunFd`）：用 `/dev/null` 顶替这个编号（`dup2`）。TUN 的引用当场释放、VPN 接口立刻拆掉；编号继续被占着，finalizer 日后关的只是占位。代价是失败一次占一个编号，直到 finalizer 运行或进程结束。`Start` 在交给 mihomo 之后一律把 `fdOwnedByMihomo` 置为 true，失败时调 `releaseTunFd` 而不走 defer 的普通 close，不会关两次。
- 顶替之前先读 `/proc/self/fd/<fd>` 确认它现在还是 `/dev/tun`：万一依赖升级后出现了「mihomo 已经把它关了」的新路径，编号可能已被 tsnet 或 mihomo 的 socket 复用，这时再 `dup2` 会把 /dev/null 盖到活 socket 上。不是 TUN 就不动，只记一条 warn。
- 单测：`TestReplaceWithNullFreesInterfaceButKeepsNumber`（用 pipe 的读端代替 TUN：释放后写端 EPIPE、编号仍是字符设备）、`TestIsTunLink`、`TestReleaseTunFdLeavesNonTunAlone`。

## 同一进程里停止再启动：要清掉 mihomo 记着的上一份 TUN 配置

- 症状：VPN 停止后在同一进程里再启动，状态显示「已连接」、tailnet 是 Running、`GET /proxies` 与测速都正常，但手机上所有流量超时，`/connections` 为空，mihomo 一条日志都没有。`GET /configs` 里 `tun.enable` 仍是 true、`file-descriptor` 是个正常的编号。冷启动（新进程）不受影响，所以只在「停止再启动」时才撞到。
- 原因：`executor.Shutdown()` 只关 TUN 监听，不清 `listener.LastTunConf`。下一次 `ReCreateTun` 拿新配置和 `LastTunConf` 比较，相等就视为没变、跳过重建。Android 会复用 fd 编号，新旧配置（含 `file-descriptor`）于是常常相等，TUN 监听就一直是关着的。
- 修法：`ptcore.teardown` 在 `executor.Shutdown()` 之后把 `listener.LastTunConf` 重置为空（它是导出变量，不用改 mihomo 源码）。
- 验证：真机上连续「启动 → 停止 → 启动」，第二次之后 `scripts/chain-check.sh` 的出口 IP、tailnet 探针都通。

## 不声明 `setMetered(false)`：VPN 跟随底层网络的计费属性

早期 `VpnService.Builder` 里写了 `setMetered(false)`，结果蜂窝下 VPN 也被所有 App 当成不计流量网络，「仅 Wi-Fi」的备份和更新会走流量。去掉之后，API 29+ 的 VPN 默认继承底层网络的计费属性（`setUnderlyingNetworks(null)` 时按系统默认网络），App 看到的计费状态与没开 VPN 时一致。别再加回去。
