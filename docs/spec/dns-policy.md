# Petrel 自动 DNS 与国内分流

2026-10-08，用户要求把 keystone 上验证过的策略放进 Petrel，今后导入配置也自动具备。实现位于 `core/ptcore/dns_policy.go`，不 fork mihomo。

## 生效边界

默认在 `injectConfig` 应用，因此导入校验、VPN 启动（包括已有配置升级后启动）走同一份变换。私有目录的原始 YAML 保留，重新导入不需要手写 DNS；仅 tailnet 模式不应用。空配置没有 Internet 代理时也不添加代理 DNS。

托管策略使用 rule 模式。主代理依次取 `petrel-dns.proxy` 显式指定、`PROXY`、有效 `MATCH` / `FINAL` 目标、唯一代理组；无法确定时拒绝校验，不能猜一个组或静默退回直连。代理名含 `&`、`#`、`=` 时拒绝用作 DNS 路由片段。选中的组如果包含并选择 DIRECT，意味着用户主动选择了直连，不是强制的代理保护。

## DNS 与路由

- 默认 DNS 为 Cloudflare / Google 的 IP 地址 DoH，通过主代理出站；不使用明文 fallback、系统 DNS、原配置的 nameserver-policy 或 direct-nameserver。未知域名同样走代理 DNS，代理故障时不自动降级直连。
- 默认解析器与代理服务器域名的引导解析使用阿里 IP 地址 DoH，独立于代理；这部分域名会被直连 DNS 服务商看到。
- 国内域名 DNS 与路由引用同一份随包固定的 `petrel-cn-domains` MRS。DNS 使用阿里 DoH DIRECT；路由在原显式规则之后、首个 `GEOIP,CN` 或 `MATCH` / `FINAL` 之前插入国内 DIRECT 规则。保留原来的规则顺序与内网 / tailnet 规则。
- 地理规则未覆盖的已验证应用端点由 `domesticAppHosts` 补充，目前仅 `mmbiz.qpic.cn`、`mmbiz.qlogo.cn` 两个精确域名。单一列表同时生成直连 DoH 与 DOMAIN 路由，紧随原显式规则、位于国内集合之前；原显式代理 / 拒绝规则优先，代理域名重叠时 DNS 仍保守地走代理。不把整个共享 CDN 后缀或全部 `.cn` 自动划为国内，不改上游 MRS 与来源清单。该默认值同样经过共同加载层，原始模式不应用。
- 原来的 `hosts`、IPv6 开关、fake-IP 范围与过滤保留。默认开启 fake-IP、关闭 HTTP/3 优先，避免多一条 QUIC 连接复用路径。
- 简单 `DOMAIN` / `DOMAIN-SUFFIX` 的 DIRECT 例外转换为直连 DoH，其余出站的域名例外使用主代理 DoH，均排在国内集合之前。DNS 的域名匹配与路由的顺序匹配不是同一个引擎：直连与代理的域名范围重叠时，DNS 保守地选代理，避免更具体的 DIRECT 域名条目覆盖在前的代理规则。复杂的逻辑、进程、IP、端口规则仍只负责连接路由，不承诺完整镜像任意自定义分流。需要精确维护私有解析策略的配置可使用原始模式。
- `petrel-cn-domains` 是保留 provider 名，托管模式下撞名就报错。资源只写 App 私有 `mihomo/rules/` 的内容寻址文件，原子替换；不下载规则、不修改用户原文件。导入失败最多安装固定资源，不更改正在运行的配置。

## 自定义配置兼容

非标准分组可以显式指定主出站：

```yaml
petrel-dns:
  proxy: 我的主代理
```

公司内网 DNS、特殊分流等需要完全自行管理时：

```yaml
petrel-dns:
  mode: original
```

该字段是 Petrel 扩展，加载时移除，不传给 mihomo。未知字段和值报错，避免误拼后静默关闭保护。原始模式仍有 Petrel 既有的 TUN 接管与入站约束。

## 节点切换

沿用 Petrel 既有行为：选择节点后断开经过该组的所有已有连接（包括内部代理 DoH），再同步清除真实 DNS 解析缓存，保留 fake-IP 映射。与 keystone 只关闭旧 DNS 连接的做法不同，Petrel 的网页连接原本就会随切换断开；本次没有扩展到 DIRECT 连接。

`SelectProxy` 与 Start / Stop 共用 lifecycleMu，避免切换与停止 / 重载交错。缓存清理直接调用 mihomo resolver，不能用异步清理冒充已完成。不持有 `mu` 调 mihomo。

托管模式另外启动会话级 DNS 路由观察器（`dns_routes.go`），每 2 秒检查主代理的有效依赖：嵌套 select / url-test / fallback、所选节点的 dialer-proxy，以及 provider 的同名实例替换。变化时只关闭目标为托管 Cloudflare / Google 的内部 DoH tracker 并同步清真实缓存，不关闭普通网页、下载、直连阿里 DNS 或无关内部连接。空配置、仅 tailnet、`mode: original` 不启动观察器。手动切换保留既有的同步断连行为，并同步观察器状态，避免下一轮重复刷新。

每轮还检查现有 DoH 连接的组链，清理首次扫描之后才完成拨号的旧选中节点连接。观察器不发测速、不触碰 lazy provider 的活跃时间；停止时取消，与 Start / Stop 同锁，旧会话不能清理新会话连接。负载均衡没有唯一出口：只观察其成员依赖集合，不能把轮询分配本身当成切换，更不能保证所有查询使用同一个国家。

上游没有切换事件订阅，因此外部 REST 和自动切换是**周期检查后的最终一致**，不是原子切换或零窗口承诺。通常下一轮检查完成清理；调度延迟、正在执行的查询与拨号可能跨越切换边界。应用内手动操作仍同步清理。外部直接重载完整内核配置不属于此观察器的托管契约，应通过 Petrel 导入并重启。

DNS 检测站标注的递归服务器国家不保证等于代理出口国家，验收看实际 DoH 连接链。应用自行使用 DoH / DoT 时也不由普通 DNS 劫持统一替换。

## 验证

Go 回归覆盖上游替换、未知域名保护、国内规则样本、原文件保留、主组推断 / 显式选择、中文组名、原始模式、资源损坏恢复及同步缓存清理。设备验证分别覆盖 Petrel 手动选择与外部 REST，检查 DNS 内部连接链、旧 tracker 消失、普通长连接保留与国内 HTTPS；自动组使用上游真实 fallback / url-test 实现验证健康状态变化，不能只用 REST Set 冒充自动切换。

### 2026-10-08 实测

开发包 `0.1.1-dev.22` 在一加 12 上覆盖安装，旧配置未重新导入、字节哈希不变，自动加载 112534 条国内域名规则。通过 App 节点页实际点击 Tokyo IIJ → Los Angeles → MiYa → Tokyo IIJ：每步查询 Google / B 站 / 淘宝，两个国外 DoH 都走当前节点、旧出口 DNS tracker 残留 0；阿里 DoH 均 DIRECT。Google HTTPS 每轮 204，B 站与淘宝每轮 200，后两者首字节 0.08–0.14 秒。最终恢复 Tokyo IIJ，无本包崩溃记录。

独立模拟器通过 DocumentsUI 导入一份 `dns.enable: false`、明文 nameserver/fallback 的无个人凭据配置，显示导入成功且保存原字节；仅代理模式启动后加载托管规则。App 选择 REJECT 时，Google 与未分类 `.cn` 域名查询失败，百度仍可解析，内部 DNS 只剩直连阿里 443，没有向旧明文上游回退。此测试验证故障时不降级，不能替代对所有第三方 App 自带 DNS 的抓包审计。

参考：[mihomo DNS 配置](https://wiki.metacubex.one/config/dns/)，以及固定依赖 v1.19.32 的 `component/resolver`、`hub/route/cache.go`、`config.parseNameServerPolicy` 源码。

### 2026-10-09 实测：自动与外部切换

在 `9be0d68` 上的未提交工作树构建测试包（显示版本仍为 `0.1.1-dev.23`），覆盖安装一加 12。外部 REST 依次切洛杉矶、MiYa、IIJ，旧 DNS tracker 均清零（本轮观测 0.36–0.53 秒，取决于检查周期相位，不代表延迟上限）；切换前建立的 HTTPS tracker 保留，切换后同一连接请求 Google 返回 204。两家 DoH 跟随新节点，阿里 DNS 仍 DIRECT，原配置字节哈希不变。

另外走 App 节点页实际点击 IIJ → 洛杉矶 → MiYa → IIJ，两个 DoH 都走当前节点，旧 DNS 残留 0；Google 四轮均 204，B 站和淘宝均 200、首字节 0.07–0.12 秒。最后恢复 IIJ 与运行中的 VPN。

独立 Android 模拟器使用无个人凭据的两个受控 SOCKS 出站：主 PROXY 始终选 AUTO，通过故意让 A 的健康探针失败来触发真正的 fallback A → B，再恢复 A，未调用 REST Set；每次旧 DoH 清零、后续 DoH 使用新节点、已有 TCP 回声连接仍可双向收发。自动故障检测到清理两轮观测共 3.86 / 3.94 秒（含健康检查周期）。另通过界面停止再启动两轮，确认进程 PID 未变且每轮 DNS 恢复，覆盖同一进程内的会话更替。Go 回归覆盖真实上游 select / fallback / url-test、嵌套与 provider 成员、前置依赖、同名替换、负载均衡不轮转、旧拨号晚到及会话隔离；Go vet、全部 Go 测试、新增测试的 race 检查和 104 项 JVM 单测均通过。

## 国内规则维护

通过仓库 `scripts/cn-rules.sh` 生成锁定同一上游提交的候选，离线重编验证后按完整哈希应用；候选附完整增删差异与原始来源，应用前保存可恢复的旧资源。流程、审查标准与中断恢复见 `core/ptcore/assets/README.md`。每次发布前检查一次上游，数据有变化时连同 App 版本部署；设备继续只用随包快照。

构建检查实际 MRS、来源元数据和国内／国外匹配样本，AAR 缓存命中也不能跳过；运行时内容寻址路径从 MRS 自动推导。2026-10-09 首次生成候选的规则与既有快照相同（新增 0、删除 0），仅升级固定提交与可复现来源记录，没有改变用户分流结果。

维护工具验证包含真实 112534 条快照的离线重编、应用、逐字节回退；Android 构建通过，缓存 AAR 时仍执行 verifyDNSRules。独立模拟器与一加 12 均加载相同的内容寻址资源，MRS 哈希与仓库一致、原 YAML 未变；手机继续使用东京 IIJ，国外 DNS 链正确，无本包崩溃。工作树测试包显示版本仍为 `0.1.1-dev.23`。
