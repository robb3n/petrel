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

## 主站通过不代表应用资源完整

2026-10-09 一加 12 反馈微信公众号正文能开，但封面 / 文内图空白、初次打开转圈。现场 `qq.com` 命中国内集合，`mmbiz.qpic.cn` 与 `mmbiz.qlogo.cn` 却不在 `cn` / `geolocation-cn` 并集；它们被默认代理 DoH 分配到海外 CDN，并走 IIJ。手机 curl 的 TLS 握手失败；仅用 `--resolve` 指向国内 DoH 返回的 CDN 地址，同域名、同手机便在约 0.06 秒完成 TLS（根路径返回 400，只能证明服务可达，不能据此宣布图片下载通过）。正文根路径约 0.32 秒返回 200；实际文章 1 秒截图仍转圈、5 秒已有文字但图片仍空白。当前规则快照与前一版字节相同，不能把此遗漏说成刚封存的规则工具改坏了数据。

修复采用可审查的两个精确应用端点默认值，同时生成 DNS 与连接规则；不扩展全部腾讯 CDN 后缀，不覆盖人的显式代理 / 拒绝，也不改原始 YAML。回归要分别看正文、封面、文内图片，并用实际上游规则匹配器验证默认直连、显式覆盖与无关域名保持代理。截图与真实资源下载才能确认用户可见恢复；首页 204 与 DNS 检测页均不能替代。

同次排查仍为 `ipv6: false`，IPv6 字面地址连接约 2 毫秒返回不可达，旧的 TUN 虚假握手问题未复现。但这是传输层结果，不保证微信内部立即回退或首屏零等待。日志中的 `ipv6.music.163.com` 经直连 DoH 核对为 A 无记录、AAAA 有记录的 IPv6 专用域名；没有播放失败证据时不伪造 IPv4 地址或擅自启用 IPv6，避免把能力探测失败误当作业务故障。

真机验证（同日）：测试包覆盖安装后，原始 YAML 哈希未变，仍选东京 IIJ。两个图片域名的 A 记录改为国内 CDN，手机不加 `--resolve` 即可完成 TLS；实际文章文内图显示正常，控制器记录图片连接为 `Domain → DIRECT` 且有下载量。公众号列表在仅返回 / 下拉后仍保留空白，重启微信（未清应用数据）后两张原先空白的大封面与缩略图均恢复。Google 204 约 0.50 秒，两家国外 DoH 仍经 IIJ；Go vet / 全量 Go 测试、104 项 JVM 测试、Android 构建以及独立模拟器 / 真机 smoke 通过。测试用独立 AVD 已删除。

首屏等待另作对照：修复后另一篇新文章 1 秒仍转圈、5 秒正文与图片已显示；重开既有文章约 3 秒已有图片但加载圈未消失。直接关 VPN 而不重启微信可能混入旧 fake-IP / 连接缓存，不作为有效性能对照。随后确认 TUN 已撤除、主站根路径直连 200 约 0.21 秒，重启微信再打开同篇文章，3 秒仍有加载圈、5 秒消失。由此只能说数秒等待在无 Petrel VPN 时也存在，不能承诺秒开或把全部首屏等待归因于 DNS；这不是严格冷缓存基准，也尚未确定剩余等待的内部原因。对照后恢复 VPN 与原 IIJ 选择。
