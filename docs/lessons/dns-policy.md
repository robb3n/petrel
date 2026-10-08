# Lessons — 自动 DNS 与节点切换

来源：2026-10-08 从 keystone 的 DNS 修复迁入 Petrel 的 inline 工作。行为规格见 `../spec/dns-policy.md`。

## 变换放在加载层，不改原始文件

只更新手机上的 `config.yaml`，下一次导入就会丢失策略。只改 `ConfigRepository` 的导入流程，旧配置升级后启动和脚本推入配置又会绕过。因此在共同的 `injectConfig` 应用，并让 ValidateConfig 与 Start 共用。

策略不 fork mihomo；随包 MRS 必须进入 Gradle 的 `buildCore` inputs，否则只更新数据、没改 Go 时 AAR 会错误地 UP-TO-DATE。规则文件使用 App 保留名字、原子写入、内容寻址；数据来源、过滤算法与许可保存在 `core/ptcore/assets/`。

## DNS 连接与 DNS 缓存不同

切换代理组不会改变已建立的 HTTP/2 DoH 连接。Petrel 本来就关闭经过该组的所有 tracker，所以内部 DoH 也在关闭范围内；补齐真实 DNS 缓存清理即可，不能顺手清掉 fake-IP 映射。

上游 `resolver.ClearCache()` 会起 goroutine。要保证 `SelectProxy` 返回时清理已完成，直接同步调用 `DefaultResolver.ClearCache()` 与 `SystemResolver.ClearCache()`。外层与 Start / Stop 串行，不能拿着保护状态的小锁 `mu` 调内核。

REST PUT `/proxies/...` 绕过 Petrel `SelectProxy`。手动回调仍需单独走界面验证；2026-10-09 新增的运行期观察器另行覆盖 REST 与自动组，不能把两个入口的验证混为一谈。

## 导入的特殊 DNS 不可无条件合并

原 `fallback`、`nameserver-policy`、`direct-nameserver`、`proxy-server-nameserver-policy` 都可能重新引入本地明文解析。托管模式只保留地址族和 fake-IP 兼容参数；特殊内网解析用明确的 `petrel-dns.mode: original`。无法确定主代理时让导入失败，不能静默选 DIRECT。

规则模式与 DNS 策略的匹配语义不同，不以 DNS 检测网站的国家标签代替实际连接链验证；第三方应用自己发 DoH 也不受普通 DNS 劫持覆盖。

Go map 经 YAML 编码会按键排序。`www...` 域名可能排在 `rule-set:...` 后面，让国内 matcher 先命中、吃掉显式代理例外。这里用 YAML mapping node 明确排列：先所有显式域名，最后国内 rule-set。重叠的 DIRECT 与代理域名统一优先代理 DNS，防止最长域名匹配与路由先到先得的差异泄露查询。

## 真机观察不能只等 REST 的 now 改变

`SelectProxy` 先 `Set`，随后关闭连接、清缓存；`/proxies/PROXY.now` 在整个回调完成之前就会变。验收脚本应在有界时间内等旧 tracker 消失，再断言新 DNS 连接的链路。瞬间看到 now 已变但旧连接尚在，不足以判定完成后的连接复用故障。

## 自动切换要观察有效依赖，不能只看主组名字

主组可能一直选中 AUTO，而 AUTO 的节点已从日本切到美国；出口节点本身也可能不变，只有 dialer-proxy 前置切换。观察器沿实际 Proxy 对象展开依赖（provider 成员未必出现在顶层 Proxies 表），保留对象身份以识别同名替换。只看 PROXY.now 会漏掉这些变化。

上游没有切换回调，不 fork 内核的方案采用 2 秒检查，明确是最终一致；不能承诺 REST 返回即已清理。LoadBalance 的 Unwrap 可能推进 round-robin，观察它时必须遍历成员而不是假装选择一次出口。

清连接要同时限制 INNER、托管解析器 IP、443 和主 DNS 代理链，不能按组名扫掉所有用户连接，也不能把所有 INNER 都当 DNS。手动切换已有的全组断连语义保留；观察器仅新增 DNS 清理。扫描后才完成的旧节点拨号通过后续组链检查收敛。停机取消与会话身份检查必须在 lifecycleMu 内重查，防止旧 goroutine 等锁后扫到新 VPN。

## 规则更新要绑定同一提交，并保留可审查差异

分别下载可变分支上的 cn 与 geolocation-cn，可能跨越上游一次发布。维护工具先固定一个 commit SHA，再取两份源，候选保存源文件哈希、过滤结果、编译器与二进制哈希。MRS 是二进制，审查需比较解码后的完整增删清单，不能只看文件大小或条数。

文件名和单测各手抄一份 SHA 会让更新遗漏。现在运行时路径从 embed 内容计算，测试与构建检查来源清单；Gradle 把 JSON 和 MRS 一起纳入输入，并在缓存命中时也执行离线校验。两次 rename 不构成事务，更新工具保留旧资源与已知新旧哈希供回退，并用锁和构建校验挡住中途状态。
