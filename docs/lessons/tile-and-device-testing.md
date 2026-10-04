# Lessons — 磁贴冷启动、首次登录与 ColorOS 真机调试

来源：首个原型（`av:dllg404i`）在一加 12（PJD110，ColorOS 16 / Android 16 / API 36）上的实测。

## 磁贴冷启动：直接起前台服务就能走通

- `am force-stop com.robb3n.petrel` 之后，用 `cmd statusbar click-tile com.robb3n.petrel/.PetrelTileService` 点磁贴，日志是 `tile: started vpn service directly`：从 TileService 直接 `startForegroundService` 没有被系统拒绝，不需要走透明 `TileLaunchActivity` 那条退路。约 4 秒后 tailnet 回到 Running，代理和 tailnet 探针都通。
- force-stop 之后进程几乎立刻又出现了。磁贴已经加到状态栏时，SystemUI 会重新绑定 TileService，把进程拉起来。所以「冷启动」时 App 进程其实已经在，只是服务没起。
- 系统对这次启动的记录是 `ActivityManager: Background started FGS: Allowed [callingPackage: com.robb3n.petrel; … uidState: CEM …]`：从磁贴在后台起前台服务被明确放行。
- 在 VPN 运行时强制停止 App，然后立刻 `click-tile`，这次点击会落空：服务没被拉起，也没有 `tile:` 日志。原因是快捷面板没展开时，SystemUI 还没重新绑定磁贴服务。先 `cmd statusbar expand-settings` 等 2–3 秒再点就正常。真实操作（下拉面板再点）天然会先绑定，脚本模拟时要补上展开这一步。
- `click-tile` 是模拟点击，真实手指点击可能不一样，最终以人亲手操作为准。
- 走直起路径时最近任务里没有卡片（`dumpsys activity recents` 里没有本包）。后来改成一律经透明 Activity 起服务，见下文「最近任务卡片」。
- 先要 `cmd statusbar add-tile <组件>` 把磁贴加到状态栏，`click-tile` 才有对象可点。

## 首次 tailnet 登录要在 Mac 上批准

tsnet 第一次启动时会打出登录链接（logcat 里的 `tailnet login URL: …`），App 界面上也有按钮。但 tailnet 没通之前代理不可用，手机上打不开需要 Google 等第三方登录的页面。所以把链接拿到 mba 上 `open`，由人在 Mac 浏览器里批准。批准之后 tsnet 状态写在 `files/tsnet/`：覆盖安装会保留它，不用再登录；卸载会丢掉。

## ColorOS 不让 adb 代授权限

- `pm grant … POST_NOTIFICATIONS` 报缺 `GRANT_RUNTIME_PERMISSIONS`；`appops set … ACTIVATE_VPN allow` 报缺 `MANAGE_APP_OPS_MODES`。VPN 授权弹框和通知权限只能人在手机上点。
- `debuggerd -j` 要 root。`run-as … kill -3` 能触发 Java 栈转储，但栈写进 `tombstoned`，没有 root 读不到。

## 其它

- 手机 shell（uid 2000）的流量也走 tun0（在 VPN 的 uid 范围里），所以 `adb shell curl` 可以直接做端到端验证，不用开浏览器。
- logcat 里每 3 秒会出现一批 `avc: denied { bind }`：网卡枚举想用 netlink，Android 11 起普通 App 不允许，mihomo 的 `anet` 会兜底，不影响功能，但会占日志。
- mihomo 的日志订阅会收到所有级别的日志（包括 debug 级的逐条 DNS 记录）。不按配置的 `log-level` 过滤就全转进 logcat 的话，会把缓冲区冲掉，logd 还会优先裁掉日志最多的 uid。tsnet 的 `[v1]` / `[v2]` 细节日志同样要丢掉。
- 本机构建出的 debug 包可以直接覆盖安装到真机，前提是本机的 debug key 和 keystone 是同一把（见 Mu3ic 的 AGENTS.md）。

## 最近任务卡片：磁贴一律经透明 TileLaunchActivity

方案先在 keystone 上的 a35 模拟器（Android 15，AOSP 系统界面）上走通，ColorOS 真机的结果见下文「ColorOS 真机补测」。

走通的方案：
- `PetrelTileService.onClick` 在未运行且已授权时，经 `startActivityAndCollapse(PendingIntent)` 拉起 `TileLaunchActivity`（透明主题、默认 affinity、不加 `excludeFromRecents` 和 `noHistory`；intent 带 `CLEAR_TOP | SINGLE_TOP`，原因见下文「ColorOS 真机补测」）。它 `onCreate` 里 `PetrelVpnService.start()` 加 `moveTaskToBack(true)`，不 finish；任务回到前台时启动 `MainActivity`（`FLAG_ACTIVITY_REORDER_TO_FRONT`）再 finish 自己。
- 模拟器上 `force-stop` 之后展开快捷设置、`click-tile`：日志依次是 `tile: launching TileLaunchActivity`、`tile launch: starting vpn from activity`、`moveTaskToBack`；`dumpsys activity recents` 里有本包的任务（root 是 `TileLaunchActivity`）；截屏是桌面，没有 Petrel 界面；VPN 起来了。
- 点桌面图标：系统把任务带回前台，`TileLaunchActivity.onRestart` 把 `MainActivity` 提到前台，截屏是主界面；返回键回桌面。
- 任务里本来就有 `MainActivity` 时：磁贴拉起后整个任务退到后台，点图标回来显示 `MainActivity`；栈里只剩一个 `MainActivity`，返回键直接回桌面，不会再弹出 `TileLaunchActivity`。
- 停止后再次从磁贴启动，同样走通，没有残留的重复任务。

没有试的退路：「磁贴拉起 `MainActivity` 并带 `EXTRA_BACKGROUND`，`setContent` 前 `moveTaskToBack`」。首选方案在模拟器上已经成立，所以没实现；如果 ColorOS 不给透明 Activity 留卡片再回头试。

### ColorOS 真机补测（2026-10-03，一加 12，`cab8da1` 的 debug 包）

磁贴操作用的是 `click-tile` 模拟点击，最近任务里的操作是 `input tap` / `input swipe` 点真实界面：
- `force-stop` 后展开面板点磁贴：VPN 起来，桌面不出界面，`dumpsys activity recents` 里有本包任务，最近任务界面有「Petrel」卡片。**缩略图是全黑的**：透明的 `TileLaunchActivity` 没画过一帧，系统没有可用的截图。功能不受影响。
- 点卡片：回到 `MainActivity`，显示「已连接」。
- 卡片没上锁时，点「清除」或上划卡片：进程、VPN、通知、卡片都没了（ColorOS 的清除就是 force-stop）。之后磁贴显示「关闭」，状态如实。
- 卡片菜单（「…」→「锁定」）锁定后点「清除」：进程、VPN、卡片都还在，45 秒后再看仍在。锁定的卡片上划也不会被杀。
- **修过的 bug：任务还留在最近任务里时，从磁贴「停止」再「启动」，VPN 不会起来，只弹出主界面**；被别的 VPN 顶掉之后再点磁贴也一样。a35 上能走通，ColorOS 上不行。原因：磁贴只带 `FLAG_ACTIVITY_NEW_TASK` 启动 `TileLaunchActivity`，而已有任务的 base intent 就是它，系统于是只把旧任务带回前台（`START … result code=2`），不建新实例。旧实例走的是 `onRestart`，那里只切到 `MainActivity`，不起服务。
  - 修法：磁贴的 intent 加 `CLEAR_TOP | SINGLE_TOP`。栈里还有 `TileLaunchActivity` 时，清掉它上面的界面并投递 `onNewIntent`；栈里没有时，在任务顶上新建一个，走 `onCreate`。旧实例会同时收到 `onNewIntent` 和 `onRestart`，两者先后不固定，所以只做标记，到 `onResume` 再决定：收到过 `onNewIntent` 就起服务，否则切到主界面。
- **修过的 bug：在主界面按返回键退出后，任务里没有 Activity 了，卡片却还在，这时点卡片只会闪回桌面。** 原因：系统用任务的 base intent 重建 `TileLaunchActivity`，又走了一遍起服务加退后台。从最近任务重建的 intent 带 `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`，`onCreate` 看到它就直接切到主界面。
- 修完后在真机和 a35 上各跑了 12 个场景，结果都正确：冷启动；栈里只有 Tile、只有 Main、主界面在前台、任务为空这几种情况下的磁贴停止再启动；点卡片；点桌面图标；按返回键。每一步都核对 VPN、前台服务、焦点窗口和任务栈。

### 被官方 Tailscale 顶掉（真机）

打开官方 App 连接（它的磁贴点了没反应：进程被 ColorOS 冻结）：两边之前都授权过，所以没有弹框，Tailscale 直接接管 tun0。Petrel 这边服务停止、通知撤掉、磁贴显示「关闭」，没有自动重连。断开并 force-stop Tailscale 之后，在 Petrel 主界面点「连接」不需要重新授权就能起来。

锁屏下的坑：
- 真机息屏加密码锁屏时 `cmd statusbar click-tile` 只会走到 `tile: launching TileLaunchActivity`，Activity 没起来，VPN 不会启动（`startActivityAndCollapse` 被锁屏挡住）。这是 `click-tile` 的模拟点击；真人在锁屏上点磁贴，系统会先要求解锁。直接 `startForegroundService` 的旧路径在锁屏下是否更宽松，没有对比测过。真机测试前先解锁并保持亮屏。

## 磁贴文案要在面板展开期间订阅状态

面板展开时磁贴一直处于监听状态，`TileService.requestListeningState` 不会再触发 `onStartListening`。原来只在 `onStartListening` 里刷新，结果在面板上点磁贴停止后，副标题还停在「待登录」，实际 VPN 已停。现在 `onStartListening` 起协程订阅 `CoreBridge.state`，`onStopListening` 取消。模拟器上验证：展开面板点停止，副标题立刻变「未连接」。

## 被别的 VPN 顶掉

模拟器上用一个只含 `VpnService` 的最小测试 App（`prepare` 加 `establish`，`appops set <pkg> ACTIVATE_VPN allow` 免弹框）顶掉 Petrel：
- logcat：`vpn revoked by system`、`state {"vpn":"stopped",…}`、`vpn stopped`。
- 常驻通知消失，系统里只剩测试 App 的 VPN；磁贴副标题「未连接」；之后没有自动重连。
- `appops set com.robb3n.petrel ACTIVATE_VPN deny` 不会触发 `onRevoke`，不能拿来模拟。
- 真机上用官方 Tailscale 顶掉的结果见上文「被官方 Tailscale 顶掉（真机）」。

## 始终开启的 VPN 与开机

模拟器上（无锁屏）：`settings put secure always_on_vpn_app com.robb3n.petrel`、`always_on_vpn_lockdown 0` 后 `adb reboot`：
- 开机完成后系统自行拉起 `PetrelVpnService`：`Start proc … for service {…PetrelVpnService}`，随后 `Background started FGS: Allowed [callingPackage: android; … intent: act=android.net.VpnService]`；`vpn started`，系统里出现 Petrel 的 VPN。
- 开着「始终开启」时按磁贴停止：`tile: stop` → `vpn stopped`，之后 3、13、38 秒各看一次，VPN 都没有被系统重新拉起，通知消失。
- 覆盖安装（`install -r`）会杀进程，系统随后自动把 VPN 重新拉起来。
- 没有验证：真机（ColorOS）开机并解锁后的拉起；未解锁时（`files/` 在 credential-encrypted 存储里读不到）会发生什么；lockdown。这几项都需要在真机上补测。

## 模拟器上授权 VPN 与通知权限

a35（google_apis 镜像）上 `appops set com.robb3n.petrel ACTIVATE_VPN allow` 和 `pm grant com.robb3n.petrel android.permission.POST_NOTIFICATIONS` 都可以直接用，不用点系统弹框。真机（ColorOS）不行，见上文。

## 在模拟器上测通知按钮

- 通知默认折叠，按钮在展开后才有：`cmd statusbar expand-notifications`，用 `uiautomator dump` 找到 Petrel 通知右侧的展开箭头点开，再找文本为「复制登录链接」的节点点它。
- tsnet 的登录链接要等一会儿才会出现（模拟器上约 25 秒，期间通知只有「断开」一个按钮）。
- 日志里的 `state {…}` 行不再带完整登录链接（`loginURL` 记为 `<set>`）；Go 层的 `tailnet login URL: …` 仍会打印链接，这是上文「首次 tailnet 登录」取链接的办法。

## Activity 重建会重放启动 intent

- **现象**：进程被杀后点最近任务卡片，或者 Activity 在后台时切了明暗（configuration change），系统用**原来的 intent** 加 `savedInstanceState` 重建 Activity，而且 intent **不带** `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`（那个标志只出现在从最近任务发起的全新启动上）。`TileLaunchActivity.onCreate` 于是走 `startFromTile()`、`MainActivity.onCreate` 走 `handleStartExtra()`，悄悄把人刚关掉的 VPN 又打开了。
- **判断方式**：重建一定带非 null 的 `savedInstanceState`，而磁贴、`EXTRA_START` 的全新启动一定是 null。所以：
  - `TileLaunchActivity.onCreate`：`savedInstanceState != null` 或 `LAUNCHED_FROM_HISTORY` 都走 `showMain()`。
  - `MainActivity.onCreate`：`savedInstanceState == null` 才处理 `EXTRA_START`。
  - 已经存活的实例收到的新磁贴请求走 `onNewIntent`，不受影响。
- 回归：改这两个 Activity 之后要把上文「修完后……12 个场景」重跑一遍，并补一条：VPN 关着时切明暗（Activity 在后台）、或杀进程后点卡片，VPN 必须保持关闭。
