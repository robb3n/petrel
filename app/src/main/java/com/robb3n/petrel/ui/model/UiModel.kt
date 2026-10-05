package com.robb3n.petrel.ui.model

import com.robb3n.petrel.ImportError
import com.robb3n.petrel.Skin
import com.robb3n.petrel.UiTone

/*
 * 皮肤无关的界面模型（docs/spec/skins.md §2.2）。三套皮肤显示同一份数据：推导在 Derive.kt 里做一次，
 * 皮肤包只渲染这里的类型、调用 [PetrelActions]，不读 repository 与 CoreBridge。
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
}

/** 连接状态的着色档，三套皮肤各自映射到自己的状态灯 / 标签样式。 */
enum class StatusTone { Idle, Busy, Warn, Live }

val ConnStatus.tone: StatusTone
    get() = when (this) {
        ConnStatus.NoConfig, ConnStatus.Off -> StatusTone.Idle
        ConnStatus.Connecting -> StatusTone.Busy
        ConnStatus.NeedsLogin, is ConnStatus.TailnetOther -> StatusTone.Warn
        ConnStatus.Connected -> StatusTone.Live
    }

/** 状态区主文字（三套皮肤一致）。 */
val ConnStatus.label: String
    get() = when (this) {
        ConnStatus.NoConfig, ConnStatus.Off -> "未连接"
        ConnStatus.Connecting -> "连接中"
        ConnStatus.NeedsLogin -> "待登录"
        ConnStatus.Connected, is ConnStatus.TailnetOther -> "已连接"
    }

/** 状态区副文字；Off 的提示各皮肤措辞不同，由皮肤传入（Shoal「点右边的开关，或用状态栏快捷开关」）。 */
fun ConnStatus.sub(offHint: String): String = when (this) {
    ConnStatus.NoConfig -> "还没有配置。导入一份 mihomo YAML 就能连接。"
    ConnStatus.Off -> offHint
    ConnStatus.Connecting -> "正在启动 tailnet 与代理"
    ConnStatus.NeedsLogin -> "登录 tailnet 之后代理才能用"
    ConnStatus.Connected -> "代理与 tailnet 都已就绪"
    is ConnStatus.TailnetOther -> "tailnet $state"
}

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

    /** 夜航的信号格，共 4 格。 */
    val bars: Int
        get() = when {
            ms <= 0 -> 0
            ms < 100 -> 4
            ms < 200 -> 3
            ms < 400 -> 2
            else -> 1
        }

    /** 大数字读数（数字与单位分开排）：夜航的出口读数与节点行、Shoal 的出口延迟格。 */
    val readout: DelayReadout
        get() = when {
            ms < 0 -> DelayReadout("—", "", timeout = false, ReadoutTone.Idle)
            ms == 0 -> DelayReadout("超时", "", timeout = true, ReadoutTone.Bad)
            // 夜航 spec §2.6：只有 Bad 档的数字用 --err，其余档沿用画稿的 --ink
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
 * [timeout] 供皮肤给汉字换小一点的字号（Barlow 的字号放不下「超时」）。
 */
data class DelayReadout(val value: String, val unit: String, val timeout: Boolean, val tone: ReadoutTone) {
    /** 值是带单位的数字。 */
    val numeric: Boolean get() = unit.isNotEmpty()
}

/**
 * 延迟的显示：测速中三套都显示「…」并用 Idle 样式。[bars] 是夜航信号格数（测速中 0），[readout] 是大数字读数。
 */
data class DelayView(val text: String, val band: Band, val readout: DelayReadout, val bars: Int)

fun Delay.view(testing: Boolean): DelayView =
    if (testing) DelayView("…", Band.Idle, DelayReadout("…", "", timeout = false, ReadoutTone.Idle), 0)
    else DelayView(text, band, readout, bars)

enum class HopRole { Tailnet, Front, Relay, Exit }

/** 链路一跳的角色说明。[detailed] 是 Shoal 的措辞：tailnet 带「· Petrel 注入」、出口写「当前选中」。 */
fun HopRole.caption(group: String, detailed: Boolean = false): String = when (this) {
    HopRole.Tailnet -> if (detailed) TS_CAPTION_DETAILED else "tailnet 第一跳"
    HopRole.Front -> "代理前置"
    HopRole.Relay -> "中转"
    HopRole.Exit -> if (detailed) "出口 · $group 当前选中" else "出口 · $group"
}

internal const val TS_CAPTION_DETAILED = "tailnet 第一跳 · Petrel 注入"

data class Hop(val name: String, val role: HopRole)

/** 夜航航线图的一个站点：本机（[role] 为 null）或某一跳；[label] 是图上的标签。 */
data class Station(val label: String, val role: HopRole?)

/** 航线图的站点 = 本机 + 每一跳，标签：本机 / tailnet / 前置 / 出口。链路为空时没有航线。 */
fun routeStations(chain: List<Hop>): List<Station> =
    if (chain.isEmpty()) {
        emptyList()
    } else {
        listOf(Station("本机", null)) + chain.map {
            Station(
                when (it.role) {
                    HopRole.Tailnet -> "tailnet"
                    HopRole.Front -> "前置"
                    HopRole.Relay -> "中转"
                    HopRole.Exit -> "出口"
                },
                it.role,
            )
        }
    }

/** 本机在 tailnet 里的摘要：IPv4、在线数、总数、直连数、中继数。 */
data class TailnetSummary(val ip: String?, val online: Int, val total: Int, val direct: Int, val relay: Int) {
    /** `5/6`（Shoal 的统计格、Tonal 的 chip）。 */
    val ratio: String get() = "$online/$total"

    /** `5 / 6`（Shoal 的读数格）。 */
    val ratioSpaced: String get() = "$online / $total"

    /** `5 / 6 在线`（三套皮肤的节点列表标题）。 */
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
}

enum class EndTone { Plain, Ok, Warn }

/** [TailnetEnd] 的读数：[text] 为 null 表示没有值（Shoal 不画，其它皮肤写「—」）。 */
data class TailnetEndView(val text: String?, val tone: EndTone)

/** [spaced] = true 写 `5 / 6 在线`（Shoal 画稿），否则 `5/6 在线`（夜航、Tonal）。 */
fun TailnetEnd.view(spaced: Boolean): TailnetEndView = when (this) {
    TailnetEnd.None -> TailnetEndView(null, EndTone.Plain)
    is TailnetEnd.Online -> TailnetEndView(if (spaced) summary.onlineLabel else "${summary.ratio} 在线", EndTone.Ok)
    TailnetEnd.NeedsLogin -> TailnetEndView("待登录", EndTone.Warn)
    is TailnetEnd.Other -> TailnetEndView(state, EndTone.Warn)
}

/** 配置摘要。日期三种写法按皮肤取：Shoal / Tonal 用 [dateShort]，夜航用 [dateNight]，配置页用 [dateTime]。 */
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
    /** `MM-dd` */
    val dateNight: String,
) {
    /** `10月3日导入`（Shoal 与 Tonal 的连接页）；没有配置时为空串。 */
    val stampShort: String get() = if (present) "$dateShort$verb" else ""

    /** `10-03 导入`（夜航的连接页）；没有配置时为空串。 */
    val stampNight: String get() = if (present) "$dateNight $verb" else ""

    /** `10月3日 17:29 导入`（配置页）；没有配置时为空串。 */
    val stampFull: String get() = if (present) "$dateTime $verb" else ""
}

/** Tonal 连接页 tailnet 色调卡的两行：大字与小字（画稿 `.tc .v` / `.tc .d`）。 */
data class TailnetCard(val value: String, val detail: String)

data class HomeUi(
    val status: ConnStatus,
    /** `CoreState.error`，空串 = 无。 */
    val error: String,
    /** 空 = 不显示链路块。 */
    val chain: List<Hop>,
    /** 出口延迟；待登录、未运行时是 [Delay.UNKNOWN]（显示「—」）。 */
    val exitDelay: Delay,
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
    /** tailnet 行的副文字：「pjd110-petrel · 100.64.0.20」。 */
    val tailnetSub: String,
    val tailnetEnd: TailnetEnd,
    /** Tonal 的 tailnet 色调卡：大字与小字。 */
    val tailnetCard: TailnetCard,
    val config: ConfigSummary,
    val importing: Boolean,
) {
    /** 出口延迟的显示：刷新中（与节点页的「测速中」同一个状态）显示「…」并用 Idle 样式，spec §2.7。 */
    val exitView: DelayView get() = exitDelay.view(refreshing)

    /** 「导入 YAML…」按钮（没有配置时的首页）。 */
    val importLabel: String get() = importButtonLabel(importing)
}

/** 导入按钮的文字：校验中「校验中…」，否则「导入 YAML…」。首页与配置页共用。 */
fun importButtonLabel(busy: Boolean): String = if (busy) "校验中…" else "导入 YAML…"

data class NodeItem(
    val name: String,
    /** 「经 a → b」，选中项再加「 · 当前出站」；没有内容时为 null。 */
    val subtitle: String?,
    /** 只有「经 a → b」（DIRECT 写「直连」），不带「当前出站」；Tonal 画稿选中项也不带。没有内容时为 null。 */
    val via: String?,
    val delay: Delay,
    val selected: Boolean,
)

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
data class BaseNode(val name: String, val isTs: Boolean, val caption: String, val status: NodeStatus?)

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
    val skin: Skin,
    /** 已实现的皮肤；只有一套时界面不显示「皮肤」行。 */
    val skins: List<Skin>,
    val tone: UiTone,
    val versionName: String,
    /** 「配置」行的副标题：当前文件名；没有配置时「还没有配置」。 */
    val configName: String,
)

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
    val setSkin: (Skin) -> Unit,
    val setTone: (UiTone) -> Unit,
    val openNodes: () -> Unit,
    val openTailnet: () -> Unit,
    val openConfig: () -> Unit,
    val openSettings: () -> Unit,
    val back: () -> Unit,
    val openTab: (Tab) -> Unit,
)
