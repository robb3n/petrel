# 国内域名快照

`cn-domains.mrs` 来自 MetaCubeX/meta-rules-dat 的 `cn.list` 与 `geolocation-cn.list`，2026-10-08 合并，112534 条。GPL-3.0，许可证见同目录 `LICENSE.meta-rules-dat`；上游地址与原始数据 SHA-256 见 `cn-domains.sources.json`。

构造：读取两个来源，丢弃空行与注释，取并集；删除 `+.` 后只有一个 DNS label 的整顶级域后缀（本次 52 条，包括 `+.cn`）；排序后用 mihomo `convert-ruleset domain text` 编译为 MRS。不能让未知的 `.cn` 域名仅凭顶级域就使用国内 DNS。

本次 MRS 的 SHA-256 为 `da4356fda44c7d069d7b86c069241bef5e9a154a1f9c98bc40e57a47c98ea9a8`。这是固定的数据资源，不含个人节点、凭据或设备配置。Go embed 随 AAR 打包，Gradle 将 MRS 纳入构建输入。

更新时重新下载两份来源、记录各自哈希、重复上述过滤与编译，检查差异，并同步 `dns_policy.go` 的内容寻址文件名、资源元数据及快照单测。运行 `go test ./...`，再在设备上验证国内 DNS / 路由与国外代理 DNS。禁止自动用上游未过滤版本覆盖。来源是域名分类表，并不能保证每个站点始终位于某一国家。

上游：[MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat)。
