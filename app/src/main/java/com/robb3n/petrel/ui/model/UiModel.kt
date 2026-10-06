package com.robb3n.petrel.ui.model

import com.robb3n.petrel.ConnMode
import com.robb3n.petrel.ExitIpPlace
import com.robb3n.petrel.ImportError
import com.robb3n.petrel.UiTone

/*
 * 界面模型（docs/spec/skins.md §2.2）：推导在 Derive.kt 里做一次（纯函数、有 JVM 单测），
 * 皮肤包（ui/skin/shoal）只渲染这里的类型、调用 [PetrelActions]，不读 repository 与 CoreBridge。
 */

/** 一级标签；二级页（配置、设置）没有标签，壳传 null。 */
enum class Tab { Home, Nodes, Tailnet }

/** 连接状态，决定状态区的文案与样式。 */
sealed interface ConnStatus {
    /** 还没有配置：开关不可用，主按钮是「导入 YAML…」。 */
    data object NoConfig : ConnStatus
    data object Off : ConnStatus
    data object Connecting : ConnStatus
    data object NeedsLogin : ConnStatus
    data object Connected : ConnStatus

    /** VPN 在跑、tailnet 既不是 Running 也不是 NeedsLogin：「已连接 / tailnet <state>」，留意色。 */
    data class TailnetOther(val state: String) : ConnStatus

    /** 仅代理模式下，当前出口的链路要经过 tailnet（`ts`）：VPN 在跑，但出口用不了。留意色。 */
    data object ExitNeedsTailnet : ConnStatus
}

/** 连接状态的着色档，皮肤映射到自己的标签样式。 */
enum class StatusTone { Idle, Busy, Warn, Live }

val ConnStatus.tone: StatusTone
    get() = when (this) {
        ConnStatus.NoConfig, ConnStatus.Off -> StatusTone.Idle
        ConnStatus.Connecting -> StatusTone.Busy
        ConnStatus.NeedsLogin, is ConnStatus.TailnetOther, ConnStatus.ExitNeedsTailnet -> StatusTone.Warn
        ConnStatus.Connected -> StatusTone.Live
    }

/** 状态区主文字。 */
val ConnStatus.label: String
    get() = when (this) {
        ConnStatus.NoConfig, ConnStatus.Off -> "未连接"
        ConnStatus.Connecting -> "连接中"
        ConnStatus.NeedsLogin -> "待登录"
        ConnStatus.Connected, is ConnStatus.TailnetOther, ConnStatus.ExitNeedsTailnet -> "已连接"
    }

/** 标题旁的状态标签：同 [label]，只有出口用不了时写「出口不可用」。 */
val ConnStatus.tagLabel: String
    get() = if (this == ConnStatus.ExitNeedsTailnet) "出口不可用" else label

/** 状态区副文字，随连接模式变。 */
fun ConnStatus.sub(offHint: String = OFF_HINT, mode: ConnMode = ConnMode.Both): String = when (this) {
    ConnStatus.NoConfig -> "还没有配置。导入一份 mihomo YAML 就能连接。"
    ConnStatus.Off -> offHint
    ConnStatus.Connecting -> when (mode) {
        ConnMode.Both -> "正在启动 tailnet 与代理"
        ConnMode.Tailnet -> "正在启动 tailnet"
        ConnMode.Proxy -> "正在启动代理"
    }
    ConnStatus.NeedsLogin -> if (mode == ConnMode.Tailnet) "登录之后才能访问 tailnet" else "登录 tailnet 之后代理才能用"
    ConnStatus.Connected -> when (mode) {
        ConnMode.Both -> "代理与 tailnet 都已就绪"
        ConnMode.Tailnet -> "仅 tailnet · 其它流量直接走手机网络"
        ConnMode.Proxy -> "仅代理 · tailnet 未启用"
    }
    is ConnStatus.TailnetOther -> "tailnet $state"
    ConnStatus.ExitNeedsTailnet -> "仅代理 · 当前出口要经过 tailnet，现在用不了"
}

/** 未连接时的提示。 */
const val OFF_HINT = "点右边的开关，或用状态栏快捷开关"

/** 延迟 / 路径的着色档。 */
enum class Band { Ok, Warn, Bad, Idle }

/** 延迟毫秒数：-1 未测，0 超时。 */
@JvmInline
value class Delay(val ms: Int) {
    val measured: Boolean get() = ms >= 0

    /** < 100 Ok，100–199 Warn，≥ 200 与超时 Bad，未测 Idle。 */
    val band: Band
        get() = when {
            ms < 0 -> Band.Idle
            ms == 0 -> Band.Bad
            ms < 100 -> Band.Ok
            ms < 200 -> Band.Warn
            else -> Band.Bad
        }

    val text: String
        get() = when {
            ms < 0 -> "—"
            ms == 0 -> "超时"
            else -> "$ms ms"
        }

    /** 大数字读数（数字与单位分开排）：连接页的出口延迟格。 */
    val readout: DelayReadout
        get() = when {
            ms < 0 -> DelayReadout("—", "", timeout = false, ReadoutTone.Idle)
            ms == 0 -> DelayReadout("超时", "", timeout = true, ReadoutTone.Bad)
            band == Band.Bad -> DelayReadout("$ms", "ms", timeout = false, ReadoutTone.Bad)
            else -> DelayReadout("$ms", "ms", timeout = false, ReadoutTone.Normal)
        }

    companion object {
        val UNKNOWN = Delay(-1)
    }
}

/** 读数的着色档：Idle（未测、测速中）、Normal、Bad（≥ 200 ms 与超时）。 */
enum class ReadoutTone { Idle, Normal, Bad }

/**
 * 大数字读数：[value] 是数字或「超时」「—」「…」，[unit] 只有数字才有（「ms」）。
 * [timeout] 表示值是汉字「超时」。
 */
data class DelayReadout(val value: String, val unit: String, val timeout: Boolean, val tone: ReadoutTone) {
    /** 值是带单位的数字。 */
    val numeric: Boolean get() = unit.isNotEmpty()
}

/** 延迟的显示：测速中显示「…」并用 Idle 样式；[readout] 是大数字读数。 */
data class DelayView(val text: String, val band: Band, val readout: DelayReadout)

fun Delay.view(testing: Boolean): DelayView =
    if (testing) DelayView("…", Band.Idle, DelayReadout("…", "", timeout = false, ReadoutTone.Idle))
    else DelayView(text, band, readout)

/** 出口要经过没开的 tailnet：徽章写「不可用」，读数是「—」。 */
val UNAVAILABLE_DELAY = DelayView("不可用", Band.Bad, DelayReadout("—", "", timeout = false, ReadoutTone.Idle))

enum class HopRole { Tailnet, Front, Relay, Exit }

/** 链路一跳的角色说明。[detailed] 是 Shoal 的措辞：tailnet 带「· Petrel 注入」、出口写「当前选中」。 */
fun HopRole.caption(group: String, detailed: Boolean = false): String = when (this) {
    HopRole.Tailnet -> if (detailed) TS_CAPTION_DETAILED else "tailnet 第一跳"
    HopRole.Front -> "代理前置"
    HopRole.Relay -> "中转"
    HopRole.Exit -> if (detailed) "出口 · $group 当前选中" else "出口 · $group"
}

internal const val TS_CAPTION_DETAILED = "tailnet 第一跳 · Petrel 注入"

/** [blocked]：这一跳是 `ts`、而 tailnet 没开（仅代理模式）。 */
data class Hop(val name: String, val role: HopRole, val blocked: Boolean = false)

/** 仅代理模式下 `ts` 那一跳的说明。 */
internal const val TS_CAPTION_DISABLED = "tailnet 第一跳 · 仅代理模式下未启用"

/** 链路卡里一跳的说明（[HopRole.caption] 的详细措辞；没开的 `ts` 另写）。 */
fun Hop.caption(group: String): String = if (blocked) TS_CAPTION_DISABLED else role.caption(group, detailed = true)

/** 本机在 tailnet 里的摘要：IPv4、在线数、总数、直连数、中继数。 */
data class TailnetSummary(val ip: String?, val online: Int, val total: Int, val direct: Int, val relay: Int) {
    /** `5/6`（统计格）。 */
    val ratio: String get() = "$online/$total"

    /** `5 / 6`（Shoal 的读数格）。 */
    val ratioSpaced: String get() = "$online / $total"

    /** `5 / 6 在线`（连接页 tailnet 行、tailnet 页的节点列表标题）。 */
    val onlineLabel: String get() = "$ratioSpaced 在线"

    /** `直连 3 · 中继 1`（Shoal 的读数格）。 */
    val pathLabel: String get() = "直连 $direct · 中继 $relay"
}

/** 连接页 tailnet 行 / 卡的右侧值。 */
sealed interface TailnetEnd {
    data object None : TailnetEnd
    data class Online(val summary: TailnetSummary) : TailnetEnd
    data object NeedsLogin : TailnetEnd
    data class Other(val state: String) : TailnetEnd

    /** 仅代理：tailnet 没开。 */
    data object Disabled : TailnetEnd
}

enum class EndTone { Plain, Ok, Warn }

/** [TailnetEnd] 的读数：[text] 为 null 表示没有值（不画）。 */
data class TailnetEndView(val text: String?, val tone: EndTone)

/** `5 / 6 在线`、`待登录`、tailnet 状态原文、`未启用`。 */
fun TailnetEnd.view(): TailnetEndView = when (this) {
    TailnetEnd.None -> TailnetEndView(null, EndTone.Plain)
    is TailnetEnd.Online -> TailnetEndView(summary.onlineLabel, EndTone.Ok)
    TailnetEnd.NeedsLogin -> TailnetEndView("待登录", EndTone.Warn)
    is TailnetEnd.Other -> TailnetEndView(state, EndTone.Warn)
    TailnetEnd.Disabled -> TailnetEndView("未启用", EndTone.Plain)
}

/** 配置摘要。日期两种写法：连接页用 [dateShort]，配置页用 [dateTime]。 */
data class ConfigSummary(
    val present: Boolean,
    val name: String,
    val imported: Boolean,
    /** 「导入」或「更新」。 */
    val verb: String,
    /** `M月d日` */
    val dateShort: String,
    /** `M月d日 HH:mm` */
    val dateTime: String,
) {
    /** `10月3日导入`（连接页）；没有配置时为空串。 */
    val stampShort: String get() = if (present) "$dateShort$verb" else ""

    /** `10月3日 17:29 导入`（配置页）；没有配置时为空串。 */
    val stampFull: String get() = if (present) "$dateTime $verb" else ""
}

/** 统计格的数字颜色（画稿 `.stat.s0 / .s2 / .s3`）。 */
enum class StatSlot { S0, S2, S3 }

/** 状态卡里的一个统计格：[value] 是数字或「—」，[unit] 只有延迟才有；点了进 [target] 标签。 */
data class StatUi(val value: String, val unit: String, val key: String, val slot: StatSlot, val target: Tab)

/** IP 类型：ip-api 的 hosting / mobile。 */
enum class IpKind(val label: String) { Hosting("机房"), Residential("住宅"), Mobile("移动网络") }

/**
 * 一个出口 IP 的各种写法，推导一次，连接页三种摆法与节点页共用。
 * [short]「美国 · 洛杉矶」，[location]「美国 · 加利福尼亚州 · 洛杉矶」，[isp]「NTT America, Inc. · AS2914」，
 * [line]「203.0.113.42 · 洛杉矶 · NTT America」（链路末跳、节点页）。
 */
data class IpView(
    val ip: String,
    /** 国家代码（「US」）；没有时「?」。 */
    val cc: String,
    val short: String,
    val location: String,
    val isp: String,
    val kind: IpKind,
    val line: String,
)

/** 连接页的出口 IP。 */
sealed interface ExitIpUi {
    /** [refreshing]：正在重查，先显示上次记下的。 */
    data class Ready(val ip: IpView, val refreshing: Boolean) : ExitIpUi

    data object Loading : ExitIpUi

    /** 查不到：[hint] 说明原因或下一步。 */
    data class Unavailable(val hint: String) : ExitIpUi
}

data class HomeUi(
    val status: ConnStatus,
    /** `CoreState.error`，空串 = 无。 */
    val error: String,
    /** 空 = 不显示链路块。 */
    val chain: List<Hop>,
    /** 出口延迟；待登录、未运行时是 [Delay.UNKNOWN]（显示「—」）。 */
    val exitDelay: Delay,
    /** 本次（或将要）连接的模式。 */
    val mode: ConnMode,
    /** 状态区副文字（随模式变）。 */
    val statusSub: String,
    /** 状态卡里的统计格；没有配置时为空。 */
    val stats: List<StatUi>,
    /** 连接页的出口 IP；null = 不显示（没在跑、出口还没就绪、没有代理组）。 */
    val exitIp: ExitIpUi?,
    /** 出口 IP 的摆法；链路卡不显示时「链路末跳」退回独立卡片。 */
    val exitIpPlace: ExitIpPlace,
    /** 出口 IP 是手机自己的网络（仅 tailnet，不经代理）。 */
    val exitIpDirect: Boolean,
    val groupName: String?,
    /** 第一个 select 组的成员数；未运行或没有组时为 null。 */
    val groupSize: Int?,
    /** 链路卡的说明：「PROXY · 手动选择 · N 个节点」；没有组时为 null。 */
    val chainSub: String?,
    /** 只有 tailnet Running 且数据到了才有。 */
    val tailnet: TailnetSummary?,
    /** 顶栏刷新按钮：VPN 在跑时可用；[refreshing] 时图标转圈、不能再点（与节点页的「测速中」同一个状态）。 */
    val canRefresh: Boolean,
    val refreshing: Boolean,
    /** tailnet 行的副文字：「pjd110-petrel · 100.64.0.20」；仅代理时「仅代理模式下未启用」。 */
    val tailnetSub: String,
    val tailnetEnd: TailnetEnd,
    val config: ConfigSummary,
    val importing: Boolean,
) {
    /** 出口延迟的显示：刷新中（与节点页的「测速中」同一个状态）显示「…」并用 Idle 样式；出口用不了时「不可用」。 */
    val exitView: DelayView
        get() = if (status == ConnStatus.ExitNeedsTailnet) UNAVAILABLE_DELAY else exitDelay.view(refreshing)

    /** tailnet 行变淡（仅代理）。 */
    val tailnetDisabled: Boolean get() = !mode.tailnetOn

    /** 仅 tailnet 时多一行「代理 · 未启用」。 */
    val proxyDisabled: Boolean get() = !mode.proxyOn

    /** 「导入 YAML…」按钮（没有配置时的首页）。 */
    val importLabel: String get() = importButtonLabel(importing)
}

/** 导入按钮的文字：校验中「校验中…」，否则「导入 YAML…」。首页与配置页共用。 */
fun importButtonLabel(busy: Boolean): String = if (busy) "校验中…" else "导入 YAML…"

/** 节点行的出口 IP。 */
sealed interface NodeIp {
    /** 没有服务器地址（组、DIRECT），不记。 */
    data object None : NodeIp

    data class Known(val ip: IpView) : NodeIp

    /** 还没记下：[text] 是「还没记下出口 IP，测延迟时补上」或补查中的「查询出口 IP…」。 */
    data class Missing(val text: String) : NodeIp
}

data class NodeItem(
    val name: String,
    /** 「经 a → b」，选中项再加「 · 当前出站」，用不了时加「 · 仅代理模式下不可用」；没有内容时为 null。 */
    val subtitle: String?,
    val delay: Delay,
    val selected: Boolean,
    /** 链路经过没开的 tailnet（仅代理）：变淡、不能选、延迟写「不可用」。 */
    val blocked: Boolean = false,
    val ip: NodeIp = NodeIp.None,
) {
    fun delayView(testing: Boolean): DelayView = if (blocked) UNAVAILABLE_DELAY else delay.view(testing)
}

data class NodeGroup(
    val name: String,
    val now: String,
    /** 「手动选择 · N 个节点」 */
    val subtitle: String,
    val members: List<NodeItem>,
)

enum class NodeStatusKind { Live, Warn, Idle }

/** 底座节点 `ts` 的状态标签：Running「已连接」、NeedsLogin「待登录」，其余显示状态名（Idle 样式）。 */
data class NodeStatus(val text: String, val kind: NodeStatusKind)

/** 链路底座：出现在 chain 里、但不是任何组成员的节点（含 `petrel-via` 标注的中转）。[status] 只有 `ts` 有。 */
/** [disabled]：`ts` 而 tailnet 没开（仅代理），图标变灰。 */
data class BaseNode(val name: String, val isTs: Boolean, val caption: String, val status: NodeStatus?, val disabled: Boolean = false)

data class NodesUi(
    val running: Boolean,
    val testing: Boolean,
    val groups: List<NodeGroup>,
    val baseNodes: List<BaseNode>,
) {
    /** 测速按钮：「测速中」/「测延迟」。 */
    val testLabel: String get() = if (testing) "测速中" else "测延迟"
}

enum class OsKind { Phone, Mac, Windows, Server }

data class PeerUi(
    val name: String,
    val ip: String?,
    val os: OsKind,
    val online: Boolean,
    /** 「直连」「中继 · hkg」「中继 · 节点」「空闲」「离线」 */
    val path: String,
    val band: Band,
    /** 离线：整行变淡。 */
    val offline: Boolean,
)

sealed interface TailnetUi {
    /** VPN 没起。 */
    data object NotActive : TailnetUi

    /** 仅代理：tailnet 没开。 */
    data object Disabled : TailnetUi

    /** [hostname] 是本机在 tailnet 里的节点名，待批准的那一行显示它。 */
    data class NeedsLogin(val loginURL: String, val hostname: String) : TailnetUi {
        val ready: Boolean get() = loginURL.isNotEmpty()
    }

    /**
     * 已登录（含 Starting 等过渡态）。[selfName]、[summary]、[peers] 为 null 表示数据还没到（显示「—」）。
     */
    data class Running(
        /** tailnet 状态原文，非 Running 时在标签里显示。 */
        val state: String,
        val connected: Boolean,
        val selfName: String?,
        val selfIp: String?,
        val summary: TailnetSummary?,
        val peers: List<PeerUi>?,
    ) : TailnetUi {
        /** 顶栏状态标签：「已连接」，否则 tailnet 状态原文。 */
        val statusLabel: String get() = if (connected) "已连接" else state

        /** 标签的着色档：已连接 Live，其余 Warn。 */
        val statusTone: StatusTone get() = if (connected) StatusTone.Live else StatusTone.Warn
    }
}

/** 「加载时 Petrel 会改写这些」卡片的一行。 */
data class RewriteItem(val key: String, val desc: String)

/** 首次使用的一步。 */
data class FirstUseStep(val title: String, val desc: String)

data class ConfigUi(
    val config: ConfigSummary,
    val importing: Boolean,
    val error: ImportError?,
    /** 「已就绪 / 文件损坏 / 未找到」；还没算出来时「—」。 */
    val geoText: String,
    /** GeoIP 库文件损坏：状态文字用错误色。 */
    val geoBad: Boolean,
    val geoBusy: Boolean,
    val rewrites: List<RewriteItem> = PETREL_REWRITES,
) {
    /** 导入失败时当前配置继续使用。 */
    val stillInUse: Boolean get() = error != null

    /** 「校验通过」标签：界面导入过且这次没失败。失败后改为说明行里的「仍在使用」，两者不同时出现（spec 配置页）。 */
    val verified: Boolean get() = config.imported && !stillInUse

    /** 「导入 YAML…」按钮。 */
    val importLabel: String get() = importButtonLabel(importing)

    /** GeoIP「替换…」按钮：校验中「校验中…」。 */
    val geoButtonLabel: String get() = if (geoBusy) "校验中…" else "替换…"

    /** 当前配置卡的说明行：「10月3日 17:29 导入」，失败时接「 · 仍在使用」；没有配置时「还没有配置」。 */
    val timeLine: String
        get() {
            val time = if (!config.present) NO_CONFIG_TEXT else config.stampFull
            return if (config.present && stillInUse) "$time · 仍在使用" else time
        }
}

data class SettingsUi(
    val mode: ConnMode,
    val tone: UiTone,
    val exitIpPlace: ExitIpPlace,
    val versionName: String,
    /** 「配置」行的副标题：当前文件名；没有配置时「还没有配置」。 */
    val configName: String,
)

/** 设置页「连接」浮岛下的说明：随两个开关的组合变。 */
val ConnMode.settingsDesc: String
    get() = when (this) {
        ConnMode.Both -> "两个都开（默认）：tailnet 是代理链的第一跳，全部流量按配置分流。"
        ConnMode.Tailnet -> "只开 tailnet：只接管 tailnet 地址，其它流量完全不经过 VPN。至少要开一个。"
        ConnMode.Proxy -> "只开代理：不启动 tailnet，配置里经过 ts 的节点这时不可用。至少要开一个。"
    }

/** 皮肤能调用的全部动作。皮肤包里不得直接碰 repository / CoreBridge。 */
class PetrelActions(
    val toggle: () -> Unit,
    val importConfig: () -> Unit,
    val replaceGeoIp: () -> Unit,
    val select: (group: String, node: String) -> Unit,
    val testDelay: () -> Unit,
    /** 首页顶栏的刷新：预热 + 测所有组 + 重拉 tailnet 节点列表。 */
    val refresh: () -> Unit,
    val logout: () -> Unit,
    val openLogin: () -> Unit,
    val copyLogin: () -> Unit,
    val copyAddress: (ip: String) -> Unit,
    val copyExitIp: (ip: String) -> Unit,
    /** 设置页「导出日志」：拼好日志文件，经系统分享面板发出。 */
    val exportLogs: () -> Unit,
    val setTone: (UiTone) -> Unit,
    val setExitIpPlace: (ExitIpPlace) -> Unit,
    /** 设置里的两个开关；关掉最后一个开着的不生效。VPN 开着时会重连一次。 */
    val setTailnetOn: (Boolean) -> Unit,
    val setProxyOn: (Boolean) -> Unit,
    val openNodes: () -> Unit,
    val openTailnet: () -> Unit,
    val openConfig: () -> Unit,
    val openSettings: () -> Unit,
    val back: () -> Unit,
    val openTab: (Tab) -> Unit,
)
