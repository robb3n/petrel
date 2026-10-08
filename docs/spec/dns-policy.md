# Petrel 自动 DNS 与国内分流

2026-10-08，用户要求把 keystone 上验证过的策略放进 Petrel，今后导入配置也自动具备。实现位于 `core/ptcore/dns_policy.go`，不 fork mihomo。

## 生效边界

默认在 `injectConfig` 应用，因此导入校验、VPN 启动（包括已有配置升级后启动）走同一份变换。私有目录的原始 YAML 保留，重新导入不需要手写 DNS；仅 tailnet 模式不应用。空配置没有 Internet 代理时也不添加代理 DNS。

托管策略使用 rule 模式。主代理依次取 `petrel-dns.proxy` 显式指定、`PROXY`、有效 `MATCH` / `FINAL` 目标、唯一代理组；无法确定时拒绝校验，不能猜一个组或静默退回直连。代理名含 `&`、`#`、`=` 时拒绝用作 DNS 路由片段。选中的组如果包含并选择 DIRECT，意味着用户主动选择了直连，不是强制的代理保护。

## DNS 与路由

- 默认 DNS 为 Cloudflare / Google 的 IP 地址 DoH，通过主代理出站；不使用明文 fallback、系统 DNS、原配置的 nameserver-policy 或 direct-nameserver。未知域名同样走代理 DNS，代理故障时不自动降级直连。
- 默认解析器与代理服务器域名的引导解析使用阿里 IP 地址 DoH，独立于代理；这部分域名会被直连 DNS 服务商看到。
- 国内域名 DNS 与路由引用同一份随包固定的 `petrel-cn-domains` MRS。DNS 使用阿里 DoH DIRECT；路由在原显式规则之后、首个 `GEOIP,CN` 或 `MATCH` / `FINAL` 之前插入国内 DIRECT 规则。保留原来的规则顺序与内网 / tailnet 规则。
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

这里只覆盖 Petrel 界面调用 `SelectProxy` 的手动切换；外部 REST 控制器与自动代理组的切换不经过此回调。DNS 检测站标注的递归服务器国家不保证等于代理出口国家，验收看实际 DoH 连接链。应用自行使用 DoH / DoT 时也不由普通 DNS 劫持统一替换。

## 验证

Go 回归覆盖上游替换、未知域名保护、国内规则样本、原文件保留、主组推断 / 显式选择、中文组名、原始模式、资源损坏恢复及同步缓存清理。设备验证需走 Petrel 自己的节点选择入口，并检查 DNS 内部连接链与国内 HTTPS；REST PUT 不能代替本次切换回调的验收。

### 2026-10-08 实测

开发包 `0.1.1-dev.22` 在一加 12 上覆盖安装，旧配置未重新导入、字节哈希不变，自动加载 112534 条国内域名规则。通过 App 节点页实际点击 Tokyo IIJ → Los Angeles → MiYa → Tokyo IIJ：每步查询 Google / B 站 / 淘宝，两个国外 DoH 都走当前节点、旧出口 DNS tracker 残留 0；阿里 DoH 均 DIRECT。Google HTTPS 每轮 204，B 站与淘宝每轮 200，后两者首字节 0.08–0.14 秒。最终恢复 Tokyo IIJ，无本包崩溃记录。

独立模拟器通过 DocumentsUI 导入一份 `dns.enable: false`、明文 nameserver/fallback 的无个人凭据配置，显示导入成功且保存原字节；仅代理模式启动后加载托管规则。App 选择 REJECT 时，Google 与未分类 `.cn` 域名查询失败，百度仍可解析，内部 DNS 只剩直连阿里 443，没有向旧明文上游回退。此测试验证故障时不降级，不能替代对所有第三方 App 自带 DNS 的抓包审计。

参考：[mihomo DNS 配置](https://wiki.metacubex.one/config/dns/)，以及固定依赖 v1.19.32 的 `component/resolver`、`hub/route/cache.go`、`config.parseNameServerPolicy` 源码。
