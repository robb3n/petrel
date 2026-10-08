# 国内域名快照

`cn-domains.mrs` 随 App 发布，来源是 [MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat) 的 `cn.list` 与 `geolocation-cn.list`。GPL-3.0，许可证见 `LICENSE.meta-rules-dat`。版本、上游固定提交、原始文件 SHA-256、过滤条目、编译器版本与最终 MRS 哈希以 `cn-domains.sources.json` 为唯一清单，不再手抄到 Go 常量与说明文档。

策略是并集、去重、排序，过滤所有单 label 模式（包括 `+.cn`、`*.cn`、`.cn` 等整个顶级域的匹配），拒绝未知规则语法与格式错误，再用仓库 `go.mod` 锁定的 mihomo 编译器生成 MRS。保留精确域名及合法前缀 `+.`、`*.`、`.`；不允许任意正则或规则指令。分类表不保证每个域名永远属于国内，新增规则需要审查，条数正常不等于安全。

## 更新契约

更新只在项目仓库进行，**不增加手机联网更新服务或后台定时下载**。每次发布前检查一次上游；发现国内站点分流错误时也检查。生成候选、审查差异、运行验证后随 App 的正常交付流程发布。网络故障或未接受的候选不影响随包快照，离线启动不依赖上游服务。

从仓库根运行 `scripts/cn-rules.sh`。下列命令是维护者／Agent 的可复现入口，普通 App 使用者无需操作。

```bash
# 只生成候选；目标目录必须不存在。--ref 可指定旧提交以重建历史版本。
scripts/cn-rules.sh prepare --dir /tmp/petrel-cn-candidate --ref meta
scripts/cn-rules.sh verify --dir /tmp/petrel-cn-candidate

# 检查 report.json、added.txt、removed.txt；SHA 必须取自已审查候选。
scripts/cn-rules.sh apply --dir /tmp/petrel-cn-candidate --sha256 <候选的完整MRS哈希>
scripts/cn-rules.sh verify

# 恢复这次更新前的两个资源文件；不会修改源码、配置或其他工作树改动。
scripts/cn-rules.sh rollback --dir /tmp/petrel-cn-candidate/previous --sha256 <旧MRS的完整哈希>
```

相对 `--dir` 路径基于 `core/`，建议使用绝对路径。候选可放仓库的 `.anvil/scratchpad/`，不要把候选原始数据、临时备份或工具锁提交；仓库仅保留最终 MRS 和来源清单。

`prepare` 先解析 `meta` 到同一个完整 commit SHA，再下载该提交的两份来源。候选保存原始输入、基线、完整增删清单和报告。`verify --dir` 不联网：逐一校验哈希、重新过滤与编译，确认二进制、过滤列表、基线和差异都一致。`apply` 要求审查过的完整 MRS 哈希，重新验证候选，若仓库基线已变化则拒绝覆盖，并在写入前保存 `previous/` 与事务记录。SHA 是审查对象绑定与完整性检查，不是上游可信性的证明。

## 校验与恢复

- 核对新增规则是否会把应代理的域名交给国内 DNS，删除规则是否会让国内常用站点绕代理。大幅变化需要逐类解释；工具不靠一个比例阈值自动批准。
- 检查使用 mihomo 的实际域名匹配：百度、B 站、淘宝、京东应命中国内规则；Google、YouTube、未知 `.cn` / `.com` 不应命中。单测覆盖坏格式、全 TLD、篡改、下载失败、基线冲突、回退及中途写入状态。
- Gradle 在 AAR 缓存命中时也运行离线校验；MRS 与 JSON 都是 AAR 构建输入。直接运行 `core/build.sh` 也校验。运行时资源路径由内嵌 MRS 哈希推导，不再手动修改文件名或测试中的固定哈希。
- 应用两个文件不是一次文件系统事务：普通错误恢复旧 MRS；进程中断时，保留的 `previous/` 可恢复已知的新旧组合，构建校验会拒绝哈希不一致的组合。工具用 `assets/.cn-rules.lock` 防并发。若进程被杀留下锁，先确认更新进程已退出，再移除该空目录并执行回退；不凭目录存在就杀进程。
- 回退仅接受事务记录中的已知文件哈希，遇到他人改动拒绝覆盖。已经部署到手机的版本需要重新构建、部署，仓库回退不会静默热改运行中的设备。
- 数据变化时，完成 Go 检查、构建与设备上的国内 DNS／路由、国外 DoH 联合验证后再交付；仅来源元数据变化而 MRS 字节不变，不宣称获得了新的规则覆盖。

2026-10-09 首轮工具验证：固定上游提交 `32bbb6c45c63a491c571f98cd9d863d7fd6771dc`，候选与旧 MRS 字节相同；112534 条、过滤 52 条，全量差异新增 0／删除 0。本次只补齐固定提交元数据和维护流程，未改变分流集合。
