# Petrel（海燕）

Android 常驻代理 App：把 mihomo 内核和一个内置 tailnet 节点（tsnet）装进同一个 VPN，tailnet 作为代理链的第一跳；状态栏快捷开关一按即冷启动。取代「官方 Tailscale App 与代理 App 只能二选一」的局面。

> applicationId = `com.robb3n.petrel` · 启动器显示名「Petrel」 · 许可证 GPL-3.0-or-later（`LICENSE`）
>
> 本文件是项目契约（给人和 coding agent 读）。维护者本机的环境事实与个人工作流放在仓库根目录的 `AGENTS.local.md`（不入库）：**文件存在时先读它**；它只补充本机事实，不覆盖本文件，冲突以本文件为准。

## 设计 / 边界

### 为什么存在

- Android 每个用户空间同一时刻只允许一个 VpnService。官方 Tailscale 本身就是 VpnService，任何 TUN 模式代理一启动就会把它顶掉。
- 机队的代理前置只在 tailnet 上监听，手机上的代理本身依赖 tailnet：两者是叠加关系，不是并列关系。所以合并进一个 VPN，而不是让代理退到应用层去和官方 App 共存。
- 官方 Tailscale App 的两个痛点直接定义了验收标准：快捷开关必须先打开过 App 才能用；后台管理里看不到它。

### 架构

- **两层**：原生 Kotlin + Compose App，加一层 Go 内核层（`core/`，构建成 App 可调用的 Android 库）。
- **Go 层只放两样东西**：
  - 上游 mihomo（`MetaCubeX/mihomo`）：固定 release tag，构建标签 `with_gvisor,cmfa`（同 CMFA；缺 `cmfa` 时 mihomo 建 TUN 要读 `/data/system/packages.xml`，普通 App 无权读），**不改源码、不 fork**。
  - App 自己持有的 `tsnet.Server`（`github.com/metacubex/tailscale/tsnet`）：版本跟随所锁 mihomo 的 `go.mod`，二进制里只有一份 tailscale。
- **不用 mihomo 自带的 `type: tailscale` 出站**：它把 tsnet 实例放在未导出字段里，App 拿不到状态、节点列表和登录流，tailnet 面板无从做起。
- **衔接**：tsnet 的 `Loopback()` 在 127.0.0.1 上开一个带口令的 SOCKS5（支持 UDP）。App 加载配置时注入一个名为 `ts` 的 `type: socks5` 节点，端口和口令都在运行时生成。用户配置只按名字引用 `ts`，例如代理前置节点写 `dialer-proxy: ts`。
- **单一 VpnService**：TUN 的 fd 交给 mihomo。防自环用 `addDisallowedApplication(packageName)` 把本 App 排除出 VPN，本进程的 mihomo 出站与 tsnet 的 WireGuard / DERP / 控制面连接都走底层网络。由此 **tun stack 固定为 gvisor**：system / mixed 靠内核 NAT，回包会绕过 tun 而断链。细节见 `docs/lessons/vpn-tun-stack.md`。
- **tsnet 随 VPN 启动立即 Up**，不等第一条连接。
- **网络切换**：Kotlin 侧用 `NOT_VPN + INTERNET` 的请求跟踪底层网络（不能用 `registerDefaultNetworkCallback`：它在 Android 16 上跟踪到的是自己的 VPN），有变化就经 `NotifyNetworkChanged` 去抖后调用 `tsnet.Server.Sys().NetMon.InjectEvent()` 唤醒 tsnet（tailscale 的 netmon 在 Android 上 10 分钟才轮询一次）。Android 上的网卡枚举由 mihomo `adapter/outbound/tailscale.go` 的 `init` 用 `anet` 注册（同一个 tailscale 模块，我们的 tsnet 直接受益）。首个原型实测切网自动恢复，见 `docs/lessons/network-change.md`。

### v1 功能范围

- **快捷开关（头号需求）**：
  - 冷启动：App 被强制停止、甚至从没打开过，按一下开关 VPN 就起来，tailnet 和代理都通，不弹界面。唯一例外是首次 VPN 授权时的系统弹框。
  - 状态如实：开、关、连接中。被别的 VPN 顶掉时开关同步变为关闭。
- **后台可见**：从快捷开关启动后，最近任务里也要有它的卡片，方便上锁防杀。单纯从 TileService 直接起服务不会产生任务卡片，所以开关一律拉起不显示界面的透明 `TileLaunchActivity`：它起服务后 `moveTaskToBack` 但不 finish，让任务留在最近任务里；任务回前台时（点卡片、桌面图标）再切到 `MainActivity`。模拟器与 ColorOS 真机上都实测走通：卡片锁定后一键清理、上划都杀不掉，没锁定会连同 VPN 一起被杀。任务还在时再点磁贴，靠 `CLEAR_TOP | SINGLE_TOP` 加 `onNewIntent` 才能起服务。见 `docs/lessons/tile-and-device-testing.md`。
- 兼容系统设置里的「始终开启的 VPN」，开机由系统拉起。
- **配置**：导入本地 YAML，和桌面端一样手工维护，存放在 App 私有目录。机队主机名写在配置的 `hosts:` 里，不依赖 MagicDNS。节点上可写 Petrel 自己的 `petrel-via: <名字>`，标注 mihomo 看不见的中转（如服务商的国内入口），只用于在链路与链路底座里显示；注入时读出并删掉，不交给内核。
- **节点操控**：沿用 MihomoBar 那套——PROXY 组切换、测延迟。
- **tailnet 面板**：
  - 登录 / 登出：配置里不写 auth-key，tsnet 通知里的登录链接做成按钮。
  - 本机状态，以及节点列表（是否在线、直连还是中继）。
- **三套皮肤**：Shoal（默认）、夜航、Tonal 三种界面，设置页里切换，明暗另有「跟随系统 / 浅 / 深」三档；行为、数据与画稿没画到的页以 `docs/spec/skins.md` 为准，视觉真相源是 `docs/spec/assets/ui-skins/`。
- **v1 不做**：订阅、多份配置、规则编辑、分应用代理、exit node、Taildrop。

### 硬边界

- 配置文件、节点凭据、tailnet 状态与任何密钥**永不进仓库**。仓库按公开的标准管理：内嵌 mihomo 决定了许可证只能是 GPL-3（本项目取 GPL-3.0-or-later）。
- 不 fork mihomo。内核问题先找上游，或在 Go 层外围绕开。
- App 名与文案不使用 "Tailscale" 商标。
- 日志、check 复述、lessons 和提交里不得出现配置内容、secret、节点凭据或完整的登录 URL；唯一例外是 Go 层只打在设备 logcat 里的 `tailnet login URL:`，它是取登录链接的渠道。（Kotlin 侧的 `state` 日志把 `loginURL` 记成 `<set>`，校验报错与异常信息也不进日志。）

### 参考实现

- `MetaCubeX/ClashMetaForAndroid`（CMFA）：VpnService、TUN fd 交接、JNI 胶水层的成熟做法。只作参考，借来的代码同为 GPL-3。
- mihomo `adapter/outbound/tailscale.go`：tsnet 在 Android 上的网卡枚举与拨号接入。
- `cyenxchen/clashmi`：用 gomobile 构建 mihomo 的开源 bridge 先例。

### 测试真机与构建方式

- **验收真机**：一加 12（PJD110 国行），ColorOS 16 / Android 16 / API 36，arm64-v8a。快捷开关冷启动、最近任务卡片、保活都以真机为准。
- **Go 层构建**：用 gomobile bind 编成 AAR（`core/build.sh`），首个原型没有碰到需要改用 cgo + JNI 的限制。
- **minSdk / ABI**：`minSdk 29`；ABI 为 `arm64-v8a`（真机）和 `x86_64`（模拟器）。

## Stack

- Jetpack Compose · Kotlin · Gradle（KTS）· package `com.robb3n.petrel`
- Go 内核层：mihomo + tsnet，经 gomobile bind 构建。现用 Go 1.26、NDK `29.0.14206865`，`-androidapi 29`。
- `compileSdk` / `minSdk` / `targetSdk` 在 `app/build.gradle.kts`。

## Dev / build / run

工具链按本机现场状态核对：

- 编译：`./gradlew :app:assembleDebug`。依赖仓库优先 Aliyun maven（`settings.gradle.kts`），新依赖也先加在那里。
- 构建机与装机设备上已有的包要是同一把 debug key 签的，否则覆盖安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
- **真机才验得准的**：快捷开关冷启动、最近任务卡片、Wi-Fi / 蜂窝切换、保活。模拟器只用来验界面和基本连通。
- 测试时 tsnet 会在 tailnet 里注册节点：测试节点用 ephemeral，或固定一个测试名并及时到控制台清理，不要在 tailnet 里堆残留节点。
- 测试用 mihomo 配置以 `*.local.yaml` 命名（已被 `.gitignore` 排除），只在本机与设备上存在。

### Go 内核层

- 前置（一次性）：`go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest && gomobile init`；需要 `ANDROID_NDK_HOME`（`core/build.sh` 默认取 `~/Library/Android/sdk/ndk/29.0.14206865`）。
- `./gradlew :app:assembleDebug` 会先跑 `:app:buildCore`，即 `core/build.sh`，产出 `app/libs/ptcore.aar`（不入库）。Go 源码没变时这一步是 UP-TO-DATE。
- 构建标签是 `with_gvisor,cmfa`，缺了 `cmfa` 的话 TUN 建不起来；来龙去脉见 `docs/lessons/vpn-tun-stack.md`。
- 模块下载走 `GOPROXY=https://goproxy.cn,direct`（脚本已设）。
- Go 单测：`cd core && go vet ./... && go test ./...`。

### 设备脚本

都是在本机构建，经 SSH 送到接着设备的主机，用那台主机自己的 adb 执行（为什么不隧道 adb，见 `scripts/device-smoke.sh` 开头）。主机与设备序列号由各脚本的 `PETREL_*_HOST` / `PETREL_*_SERIAL` 指定，默认值是维护者的环境。

- `scripts/device-smoke.sh`：安装、启动、检查崩溃、截图。默认在模拟器（`PETREL_SMOKE_AVD`）上跑；设 `PETREL_SMOKE_SERIAL` 改在真机上跑。退出码 0 之后仍要亲自看截图。
- `scripts/push-config.sh <config.yaml> [Country.mmdb]`：经 adb 的 stdin 把配置直接写进 debug 包的 `files/`（权限 600），设备上不落临时文件。GeoIP 库一起推，免得 mihomo 首次启动时去 GitHub 下载。写完会删掉 `files/config.meta.json`（界面导入时记的原文件名与时间），界面于是显示 `config.yaml` 与「…更新」、没有「校验通过」。
- `scripts/chain-check.sh <config.yaml> [--switch]`：用 mihomo REST 测 PROXY 组每个节点和 `ts` 的延迟，从手机 shell 发真实请求（google 204、出口 IP、tailnet 探针），列出连接链路。带 `--switch` 时把每个节点切一遍看出口 IP，最后切回原选中项。退出码 0 表示全部通过。
- 本机测试配置放在仓库外（例如 `~/.config/petrel/`，目录 700、文件 600）。
- 起 VPN：VPN 授权必须人在手机上点一次（ColorOS 不让 adb 代授）。之后用 `am start -n com.robb3n.petrel/.MainActivity --ez com.robb3n.petrel.EXTRA_START true`，或 `cmd statusbar click-tile com.robb3n.petrel/.PetrelTileService`。
- 查日志：logcat 缓冲区只有 256 KiB，要实时抓流，不要事后 dump。debug 包会把底层网络事件记到 `files/netevents.log`。见 `docs/lessons/network-change.md`。

## 版本

- 语义化，tag 带 `v` 前缀 `v0.1.0`。`app/build.gradle.kts` 保留一行字面量 `val baseVersionName`。
- **`versionCode` = git 提交计数 + 12**（`git rev-list --count HEAD` 加上开源前压掉的 12 个提交，见 `squashedCommits`；自动算，永远不手动改；同一提交重建得到同一个 build 号）。
- **`versionName` 自动推出**：HEAD 上有 tag `v<baseVersionName>` 且已跟踪文件没有未提交改动 → `<baseVersionName>`（稳定版）；否则 `<baseVersionName>-dev.<build>`（如 `0.1.0-dev.12`，未跟踪文件不算改动）。
- 升版本只改 `baseVersionName` 那一行，并在那个提交上打 tag `v<baseVersionName>`。
- 没有 git 历史时（GitHub 自动生成的源码压缩包）照样能构建：`versionName` 退成 `<baseVersionName>-src`、`versionCode` 为 1。这种包只供自行构建，不进侧载存档、不发布。

## Local Deploy

每次提交封存并 push 之后执行（本地、可逆；对外发布不在此）：

1. 在仓库根目录跑 `scripts/local-deploy.sh`。它构建 debug 包（版本号由 git 推出，所以要在那个提交之后跑），复制到侧载存档 `$PETREL_DEPLOY_DIR/Petrel-<versionName>.apk`，经 ssh 主机上的 adb 覆盖安装到真机；装之前 Petrel 的 VPN 开着的话，装完从快捷开关把它拉起来（不弹界面，不打断前台的 App）。主机、序列号、存档目录见脚本开头的环境变量。步骤只在脚本里写一份，别在这里另写 adb 命令。
2. 退出码：`0` 装好了，再看 stderr 最后的「VPN：」一行（`restored` / `was off` / `not restored（原因）`，锁屏时磁贴会被拦下，报给人解锁后点一下快捷开关）；`1` 构建 / 安装失败或装完有本包的 FATAL EXCEPTION；`10` 手机或 ssh 主机不可用（APK 已在侧载存档里），按部署失败处理，接好手机后重跑。
3. 报出侧载存档路径、装上的 versionName 和 VPN 恢复结果。

为什么用 debug 包：`scripts/push-config.sh`、`files/netevents.log` 都靠 `run-as`，只对 debuggable 包有效；界面轻，不需要 release 构建才流畅。

## Release

opt-in `/release`，只由人发起。发布的是回火任务的 stamp 打过 tag `v<baseVersionName>` 的提交；渠道是本仓库的 GitHub Releases，附 APK 与它的 sha256。

1. 前置：HEAD 就是那个 tag，已跟踪文件干净，分支与 tag 都已推到 origin（脚本第 1 步会逐条核对）。
2. 起草更新说明：取上一个 `v*` tag 以来的提交（首次发布取全部），以 `feat` / `fix` 为主，写成面向用户的中文，不写内部重构与构建细节，存成会话 scratchpad 里的临时文件。
3. `scripts/publish-release.sh --dry-run --notes-file <文件>`：只构建与校验（版本是稳定版、正式签名指纹、只有 arm64-v8a、非 debuggable），不碰 GitHub。报出 APK 路径与 sha256。
4. **人工确认闸门**：把 tag、版本、sha256 和更新说明交人确认；Release 是公开、不可逆的。
5. `scripts/publish-release.sh --notes-file <文件>`：建 Release `v<版本>`、上传 `Petrel-<版本>.apk` 与 `.sha256`，再下载回来核对 sha256。同名 Release 已存在时拒绝，不覆盖。
6. 报出 Release 网址。

签名与构建的事实：

- **release 包** = release 构建类型（非 debuggable，不开 R8）+ 正式 release key + 只含 arm64-v8a（`libgojni.so` 单个 ABI 约 60 MB）。debug 包仍是 arm64-v8a + x86_64、debug key。
- **release key**：PKCS12，别名 `petrel`，RSA 4096，有效期 100 年。文件路径与口令只在构建机 `~/.gradle/gradle.properties` 的 `petrel.release.storeFile`（绝对路径，或以 `~/` 开头）/ `storePassword` / `keyAlias` / `keyPassword`，**不进仓库、不打印到终端、日志或对话**；属性缺了，`assembleRelease` 直接失败并指出缺哪个，不会退回 debug key。工作副本与备份位置见 `AGENTS.local.md`。
- **正式证书 SHA-256**：`94096a8fa7821aab4437210421bd4879304cffab8f3d52d98a7eef2506038a7d`（同步在 `scripts/publish-release.sh` 的 `OFFICIAL_CERT_SHA256`；`apksigner verify --print-certs` 核对）。**别换这把 key**：换了，已装用户无法覆盖升级。
- release 包与 debug 包签名不同，不能互相覆盖安装：已装 debug 包的设备（如维护者的测试真机）要换成 release 包只能先卸载，tailnet 节点状态与配置会一起丢。

## 代码约定 / 经验

- **新功能 = `ui/model` 推导一次 + 三套皮肤各画一次**：数据推导（状态映射、链路、延迟分档、文案）只写在 `ui/model/`（纯函数，JVM 单测 `./gradlew :app:testDebugUnitTest`），皮肤包（`ui/skin/<shoal|night|tonal>/`）只渲染模型、调用 `PetrelActions`，**不直接读 repository 或 `CoreBridge`**。动皮肤、系统栏、冷启动底色之前先读 `docs/lessons/skins.md`。
- 项目级踩坑落 `docs/lessons/`，随 stamp 进 git（如 VPN 自环、tsnet 网络切换、各 ROM 的快捷开关与后台行为）。
- **动 VpnService 的防自环方式、tun stack 或 mihomo 构建标签之前，先读 `docs/lessons/vpn-tun-stack.md`**。三者互相牵制：本 App 排除出 VPN 就只能用 gvisor；不带 `cmfa` 标签 TUN 建不起来。
- **动网络回调、`ptcore` 的锁、tsnet 的日志或本机的 tailnet 节点名之前，先读 `docs/lessons/network-change.md`**。要点：
  - 跟踪 NOT_VPN 网络，不用 `registerDefaultNetworkCallback`。
  - 切网要调 `NetMon.InjectEvent()` 唤醒 tsnet。
  - 持有 `mu` 时不得调用 tsnet / mihomo / Host，否则会和 tailscale 的 Logf 互相卡死。
- **Kotlin 调 `Ptcore.*` 一律放在服务的 worker 线程或 `Dispatchers.IO`，不在主线程、也不在系统回调线程（含 `onDestroy`、网络回调）。** 原因：Go 调用可能卡在 tsnet 内部锁、配置解析或 geodata 下载上；主线程会 ANR，系统回调线程会堵死整个进程的网络回调。死锁的来龙去脉见 `docs/lessons/network-change.md` 的「死锁」一节。
- **动配置导入、GeoIP 替换、RESTART、服务的启动返回值（`onStartCommand` 的 STICKY / `stopSelf`）或服务的停止 / 撤通知之前，先读 `docs/lessons/config-import.md`**。
- **动磁贴、`TileLaunchActivity` / `MainActivity` 对启动 intent 的处理、首次登录流程或在 ColorOS 上调试之前，先读 `docs/lessons/tile-and-device-testing.md`**。

## Git

Conventional Commits：`<type>(<scope>): <summary>`。commit 末尾 `Co-Authored-By:` 用**当前模型**，别硬编码型号。
