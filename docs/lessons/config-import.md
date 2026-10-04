# Lessons — 配置导入、GeoIP 替换与运行中重启

来源：`av:0t25mik7`（做配置导入）在 a35 模拟器与一加 12（ColorOS 16）上的实测。

## 校验用 `ValidateConfig`，行为以实测为准

- 与 `Start` 走同一个 `injectConfig`（占位 fd 与 `127.0.0.1:1`），再交给 `executor.ParseWithBytes`；不 `ApplyConfig`、不写文件、不碰 `mu`。
- 空文件会**通过**：`injectConfig` 会补上 `tun`、`ts` 等，mihomo 放行（单测 `TestValidateConfigEmpty` 固定了这个行为）。空配置连得上但没有代理组。
- 报错原文带索引，例如 `proxy group[0]: PROXY: 'tokyo-iij' not found`（画稿里写的是不带 `[0]` 的示意文案）。界面原样显示。
- 解析期间 mihomo 会经 `temporaryUpdateGeneral` 临时改全局设置再回滚。校验超时后 Go 侧还会接着跑（不可取消），这时重试导入、或替换 GeoIP 触发 RESTART，`Start` 的解析就会与它交错，运行中内核的全局设置会错，也是数据竞争。所以 `ValidateConfig` 的解析与 `Start` 的「SetHomeDir + 解析 + ApplyConfig」整段都在专用的 `parseMu` 下串行（只锁解析的话，校验的解析仍能插进「解析完、应用中」的缝里，回滚时盖掉刚应用的设置）（不是 `mu`；锁内不调 Host）。真机上 VPN 运行时导入一份坏配置：横幅正常、进程不变、没有重启，随后 `chain-check.sh` 全通过。
- mihomo 的校验报错可能带出配置里的片段（YAML 语法错误会带行文本），所以 `ConfigRepository` 不把报错和异常信息写进日志，只记异常类名。

## 运行中重启：`running` 先落 false，通知不动

`RESTART` 在 worker 上走 `stopCore()`（注销网络回调 → `Ptcore.stop()` → `running = false`）→ `markStarting()` → `startVpn()`。
- `running` 必须先落到 false：`startVpn` 开头会把 `running` 当成「已在运行」直接返回。
- 不 `stopForeground`、不 `stopSelf`：真机与模拟器上轮询 `dumpsys notification`，重启全程通知都在；日志顺序是 `vpn restarting` → `stopped` → `starting` → `running` → `vpn started`，一轮不到 0.3 秒。
- 重启期间首页、磁贴会短暂闪一下「未连接」：Go 侧 `Stop()` 会推一次 stopped 状态，没有绕开。
- VPN 没在跑时收到 RESTART：服务在 worker 上看 `running`，为 false 就 `stopSelf(startId)`，不启动。

## 撤不掉的通知：撤通知要和 update 同在主线程

现象：连接再断开，约两到三成的情况下状态栏通知（id 1）撤不掉，服务已经不在了，`dumpsys notification` 里还有这条记录。

原因：状态收集协程在主线程调 `Notifications.update`，旧的 `stopVpn` 在 worker 线程调 `stopForeground(REMOVE)`。两路 binder 调用到达系统的先后不定，晚到的一次 update 会把刚撤掉的通知又发出来。

修法：`stopService()` 把 `stopForeground` + `stopSelf` post 到主线程，与 update 同线程、顺序确定；收集条件改成只在 `s.active` 时更新（stopped 状态没有对应文案，原来的 `running ||` 条件还会在 `Ptcore.stop()` 之后、`running = false` 之前的窗口里把「已连接」发出来）。修后在 a35 上连续六轮连接 / 断开都撤干净。

## 导入失败的横幅别用 `onDispose` 清

横幅要「一直显示，直到下一次导入开始或离开配置页」。放在配置页 composable 的 `onDispose` 里清，明暗切换和旋转屏幕重建 Activity 时也会触发，横幅就没了（对照 `ConfigError` 画板时撞到的）。现在由 `PetrelApp` 的 `OnDestinationChangedListener` 在目的地不是配置页时清。

从首页（`HomeEmpty` 的按钮）发起、导入失败时，横幅在配置页：`PetrelApp` 监听到错误就导航过去，否则没有配置时横幅无处可显示。

## 选择器与测试

- 各家文件选择器对 `*/*` 都放行 `.yaml`、`.mmdb`（模拟器 a35 的 AOSP DocumentsUI 里 `.yaml` 显示为「YAML file」）。
- a35（AOSP DocumentsUI）：点放大镜、输入文件名、回车，再点结果即可；`uiautomator dump` 拿控件，`input tap` 点。
- 一加 12（ColorOS 自带选择器，中文）：**搜索不按文件名找 `.mmdb` / `.yaml`**（它搜的是文档内容，只命中 pdf）。改走「文件」→「下载」，文件直接排在网格里。
- Toast 只显示约 2 秒，`uiautomator dump` 抓不到：点选后立刻连续 `screencap` 两张（中间隔 0.3 秒）看第一张或第二张。
- 通过 ssh 在 keystone 上跑 adb 时，远端登录 shell 是 zsh：命令里的变量不会按空格拆分，且嵌套单引号（`run-as … sh -c '…'`）会在 ssh 这一层被吃掉。要么每条 `run-as` 单独一条命令，要么先写成远端脚本文件再 `bash` 执行。

## 服务的启动返回值与 stopSelf

- **系统按最近一次 `onStartCommand` 的返回值决定进程被杀后要不要重启服务**（`stopIfKilled`）。运行中的辅助 action（`COPY_LOGIN`、`RESTART`）也要返回 `START_STICKY`，否则导入配置、替换 GeoIP、复制登录链接之后，进程被杀就不会再被拉起。服务没在跑时才返回 `START_NOT_STICKY`；`ACTION_STOP` 恒为 `START_NOT_STICKY`。判断条件是 `running || CoreBridge.state.value.active`（`active()`）。
- **`stopSelf()` 不带 startId 会连带杀掉刚到的启动请求。** 停止后马上再启动时：STOP 在 worker 上跑完 `stopVpn`，往主线程 post `stopSelf()`；在这之前到达的 START 已经 `startForeground` 并把 `startVpn` 排进队列，无条件 `stopSelf()` 照样销毁服务，结果内核和 VPN 在跑、前台服务和通知没了。现在每个停止请求带上触发它的 startId（STOP / RESTART 是各自 intent 的 id，`startVpn` 失败时是排队它的那次请求的 id，`onRevoke` 取当时的 `lastStartId`），在主线程里：之后又来了更新的 START（`latestStartRequestId > startId`）就不停，服务和通知都留着；否则 `stopSelfResult(lastStartId)`，返回 true 才 `stopForeground`。不能直接 `stopSelfResult(startId)`：COPY_LOGIN、RESTART 这类辅助请求也会推进 startId，它们排在 STOP 之后到达时，STOP 自己的 id 就不是最新的，服务会永远停不掉。`startVpn` 因服务已销毁而丢弃时，会用 `Ptcore.status()` 把入口改成「连接中」的状态纠正回去。
- **服务销毁后，worker 里排着的任务还会执行。** 旧版 `onDestroy` 里 `worker.shutdown()` 不丢弃已排队的 `startVpn`。现在 `destroyed` 标志在 `startVpn` 开头挡掉它。
- **worker 是进程级的单线程，不是每个服务实例一个。** 服务销毁再重建会换实例；旧实例 `onDestroy` 里排的 `stopCore()` 必须先于新实例的 `startVpn` 执行，共用一个队列才有这个保证。`onDestroy` 本身不在主线程调 Go（tsnet `Close` 可能阻塞，会 ANR），交给 worker。
- **START 在已运行时不改状态。** 始终开启的 VPN 重新拉起、Activity 重建、`EXTRA_START` 都会再发一次 START：入口只在 `!running` 时才 `markStarting()`，否则 `startVpn` 直接返回、没人再 publish，磁贴、通知、首页都卡在「连接中」；`startVpn` 的早退路径还会用 `Ptcore.status()` 补推一次真实状态。
- Go 侧对应：`Start` / `Stop` 整段在 `lifecycleMu` 下串行（独立于 `mu`，持有时可以调 tsnet / mihomo），两个 `Start`（新实例配新 worker）不会同时通过 `srv != nil` 检查。
