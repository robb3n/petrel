# Petrel（海燕）

Android 上的常驻代理 App：把 [mihomo](https://github.com/MetaCubeX/mihomo) 内核和一个内置的 tailnet 节点（tsnet）装进**同一个 VPN**，tailnet 作为代理链的第一跳。

> An Android proxy app that runs the mihomo core and an embedded tailnet node (tsnet) inside a single VpnService, with the tailnet as the first hop of the proxy chain.

## 为什么

Android 同一时刻只允许一个 VpnService。官方的 tailnet 客户端本身就是 VPN，任何 TUN 模式的代理一开就会把它顶掉；而如果你的代理前置节点只在 tailnet 里监听，代理本身又离不开 tailnet。Petrel 把两者合进一个 VPN：mihomo 接管 TUN，tsnet 在同一进程里提供到 tailnet 的通路。

## 功能

- 状态栏快捷开关一按即起：App 被强制停止、甚至从没打开过也能冷启动，不弹界面；兼容系统的「始终开启的 VPN」。
- 导入本地 mihomo YAML 配置；Petrel 注入一个名为 `ts` 的节点，配置里用 `dialer-proxy: ts` 让代理前置走 tailnet。
- 节点页：切换 select 组、测延迟；连接页每 30 秒刷新当前出口的延迟；链路与「链路底座」一目了然。
- 节点上可写 Petrel 自己的 `petrel-via: <名字>`，标注 mihomo 看不见的中转（例如服务商的国内入口），只用于显示。
- tailnet 面板：登录 / 登出、本机状态、对端节点列表（在线、直连还是中继）。
- 连接模式：tailnet + 代理（默认）、仅 tailnet（只接管 tailnet 地址，同官方客户端）、仅代理（不起 tailnet）。
- 连接页显示当前出口的 IP 与归属地（运营商、机房 / 住宅），节点页显示各节点记下的出口 IP；查询走 ip-api.com。
- 明暗可跟随系统。

v1 不做：订阅、多份配置、规则编辑、分应用代理、exit node。

## 构建

需要 JDK 17、Android SDK（compileSdk 35）、NDK `29.0.14206865`、Go 1.26 与 gomobile：

```bash
go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
gomobile init
./gradlew :app:assembleDebug     # 会先跑 core/build.sh，把 Go 层编成 app/libs/ptcore.aar
```

Go 单测：`cd core && go vet ./... && go test ./...`；界面模型单测：`./gradlew :app:testDebugUnitTest`。

架构、边界与踩过的坑见 [`AGENTS.md`](AGENTS.md) 与 [`docs/lessons/`](docs/lessons/)；界面规格见 [`docs/spec/`](docs/spec/)。

## 许可证

Petrel 以 [GPL-3.0-or-later](LICENSE) 发布（内嵌的 mihomo 是 GPL-3.0）。第三方组件的许可证见 [`licenses/`](licenses/) 与 App 内「设置 → 开源许可」。

Petrel 与 Tailscale Inc. 无关，也未获其认可；“Tailscale” 是其所有者的商标。
