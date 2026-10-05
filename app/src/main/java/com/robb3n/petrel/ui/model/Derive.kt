package com.robb3n.petrel.ui.model

import com.robb3n.petrel.ConfigInfo
import com.robb3n.petrel.CoreState
import com.robb3n.petrel.GeoIpStatus
import com.robb3n.petrel.ImportUi
import com.robb3n.petrel.PeerPath
import com.robb3n.petrel.ProxyGroupUi
import com.robb3n.petrel.ProxyUi
import com.robb3n.petrel.Skin
import com.robb3n.petrel.TAILNET_NEEDS_LOGIN
import com.robb3n.petrel.TAILNET_RUNNING
import com.robb3n.petrel.TailnetPeerUi
import com.robb3n.petrel.TailnetStatusUi
import com.robb3n.petrel.UiTone
import com.robb3n.petrel.firstIPv4
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * 把 CoreState / repository 的数据推导成各屏的模型。纯函数、无 Android 依赖，JVM 单测见 app/src/test。
 */

fun connStatus(s: CoreState, configPresent: Boolean): ConnStatus = when {
    !configPresent -> ConnStatus.NoConfig
    s.vpn == "starting" -> ConnStatus.Connecting
    !s.running -> ConnStatus.Off
    s.tailnet == TAILNET_RUNNING -> ConnStatus.Connected
    s.tailnet == TAILNET_NEEDS_LOGIN -> ConnStatus.NeedsLogin
    else -> ConnStatus.TailnetOther(s.tailnet)
}

/**
 * 当前链路：第一个组当前选中成员的 chain（Go 层给的就是「第一跳 → … → 成员自己」，先 ts、再前置、最后出口）。
 * 最后一跳是出口，名字是 `ts` 的是 tailnet 第一跳，成员 relays 里的是中转，其余是代理前置。只有一跳时只有出口；没有组或找不到选中成员时为空。
 */
fun chainOf(groups: List<ProxyGroupUi>): List<Hop> {
    val g = groups.firstOrNull() ?: return emptyList()
    val member = g.proxies.firstOrNull { it.name == g.now } ?: return emptyList()
    val last = member.chain.lastIndex
    return member.chain.mapIndexed { i, name ->
        val role = when {
            i == last -> HopRole.Exit
            name == "ts" -> HopRole.Tailnet
            name in member.relays -> HopRole.Relay
            else -> HopRole.Front
        }
        Hop(name, role)
    }
}

/** 成员的副标题：「经 a → b」是 chain 去掉自己后倒序；只有自己时 DIRECT 写「直连」，其它不显示。 */
fun proxySubtitle(p: ProxyUi): String? {
    val via = p.chain.dropLast(1).asReversed()
    return when {
        via.isNotEmpty() -> "经 " + via.joinToString(" → ")
        p.name == "DIRECT" -> "直连"
        else -> null
    }
}

/** 出现在任意 chain 里、但不是任何已列出组成员的节点，按首次出现的顺序。 */
fun baseNodes(groups: List<ProxyGroupUi>): List<String> {
    val members = groups.flatMap { g -> g.proxies.map { it.name } }.toSet()
    return groups.flatMap { g -> g.proxies.flatMap { it.chain } }.distinct().filter { it !in members }
}

fun osKind(os: String): OsKind = when (os.lowercase(Locale.ROOT)) {
    "android", "ios" -> OsKind.Phone
    "macos" -> OsKind.Mac
    "windows" -> OsKind.Windows
    else -> OsKind.Server
}

/** 对端路径的文案与着色档；离线整行变淡。 */
fun peerPath(path: PeerPath, relay: String): Pair<String, Band> = when (path) {
    PeerPath.Direct -> "直连" to Band.Ok
    PeerPath.Relay -> (if (relay.isEmpty()) "中继" else "中继 · $relay") to Band.Warn
    PeerPath.PeerRelay -> "中继 · 节点" to Band.Warn
    PeerPath.Idle -> "空闲" to Band.Idle
    PeerPath.Offline -> "离线" to Band.Idle
}

fun peerUi(p: TailnetPeerUi): PeerUi {
    val (text, band) = peerPath(p.path, p.relay)
    return PeerUi(
        name = p.name,
        ip = p.ips.firstIPv4(),
        os = osKind(p.os),
        online = p.online,
        path = text,
        band = band,
        offline = p.path == PeerPath.Offline,
    )
}

/** 直连 = Direct 数，中继 = Relay + PeerRelay 数，在线 = onlineCount。 */
fun tailnetSummary(s: CoreState, status: TailnetStatusUi): TailnetSummary = TailnetSummary(
    ip = status.self.ips.firstIPv4() ?: s.tailnetIPs.firstIPv4(),
    online = status.onlineCount,
    total = status.peers.size,
    direct = status.peers.count { it.path == PeerPath.Direct },
    relay = status.peers.count { it.path == PeerPath.Relay || it.path == PeerPath.PeerRelay },
)

private fun format(pattern: String, millis: Long, zone: TimeZone): String =
    SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = zone }.format(Date(millis))

fun configSummary(info: ConfigInfo, zone: TimeZone = TimeZone.getDefault()): ConfigSummary = ConfigSummary(
    present = info.present,
    name = info.name,
    imported = info.imported,
    verb = if (info.imported) "导入" else "更新",
    dateShort = if (info.present) format("M月d日", info.timeMillis, zone) else "",
    dateTime = if (info.present) format("M月d日 HH:mm", info.timeMillis, zone) else "",
    dateNight = if (info.present) format("MM-dd", info.timeMillis, zone) else "",
)

/** 连接页 tailnet 行：[sub] 是完整副文字（「pjd110-petrel · 100.64.0.20」），[detail] 是去掉本机名前缀的部分（Tonal 的色调卡小字）。 */
internal data class TailnetRow(val sub: String, val detail: String, val end: TailnetEnd)

/** 连接页 tailnet 行的副文字与右侧值，见 spec §2.7「tailnet 摘要」。[hostname] 是本机在 tailnet 里的节点名。 */
internal fun tailnetRow(status: ConnStatus, summary: TailnetSummary?, selfIp: String?, hostname: String): TailnetRow {
    fun withHost(ip: String?) = if (ip != null) TailnetRow("$hostname · $ip", ip, TailnetEnd.None) else TailnetRow(hostname, hostname, TailnetEnd.None)
    return when (status) {
        ConnStatus.Connected ->
            withHost(summary?.ip ?: selfIp).copy(end = summary?.let { TailnetEnd.Online(it) } ?: TailnetEnd.None)
        ConnStatus.NeedsLogin -> TailnetRow("$hostname · 等待批准", "等待批准", TailnetEnd.NeedsLogin)
        is ConnStatus.TailnetOther -> withHost(summary?.ip ?: selfIp).copy(end = TailnetEnd.Other(status.state))
        ConnStatus.Connecting -> TailnetRow("正在启动", "正在启动", TailnetEnd.None)
        ConnStatus.Off, ConnStatus.NoConfig -> TailnetRow("未启动", "未启动", TailnetEnd.None)
    }
}

/**
 * Tonal 连接页 tailnet 卡：大字 = 右侧值（`5/6 在线`、`待登录`、tailnet 状态原文、没有时「—」），
 * 小字 = [TailnetRow.detail]（画稿只写 IP：`100.64.0.20`、`等待批准`、`正在启动`、`未启动`）。
 */
fun tailnetCard(detail: String, end: TailnetEnd): TailnetCard = TailnetCard(end.view(spaced = false).text ?: "—", detail)

/** 组的说明：「手动选择 · N 个节点」。节点页的组卡头与连接页的链路卡共用。 */
fun groupSubtitle(size: Int): String = "手动选择 · $size 个节点"

/** 底座节点 `ts` 的状态：只有 Running / NeedsLogin 有专门文案，其余显示 tailnet 状态原文。 */
fun tsStatus(tailnetState: String): NodeStatus = when (tailnetState) {
    TAILNET_RUNNING -> NodeStatus("已连接", NodeStatusKind.Live)
    TAILNET_NEEDS_LOGIN -> NodeStatus("待登录", NodeStatusKind.Warn)
    else -> NodeStatus(tailnetState, NodeStatusKind.Idle)
}

fun geoText(geo: GeoIpStatus?): String = when (geo) {
    GeoIpStatus.Ready -> "已就绪"
    GeoIpStatus.Corrupt -> "文件损坏"
    GeoIpStatus.Missing -> "未找到"
    null -> "—"
}

/** 配置页「加载时 Petrel 会改写这些」。改 [injectConfig] 的行为时同步这里。 */
val PETREL_REWRITES: List<RewriteItem> = listOf(
    RewriteItem("ts", "注入的 tailnet 节点，用 dialer-proxy: ts 引用"),
    RewriteItem("tun", "接管为 Petrel 的 VPN，固定 gvisor"),
    RewriteItem("external-controller", "固定 127.0.0.1:9090，没写 secret 时随机生成；其余 controller 入口一律删除"),
    RewriteItem("interface-name · routing-mark", "删除（桌面端残留），顶层与节点上的都删"),
    RewriteItem("listeners · tunnels", "入站保留，监听地址改成 127.0.0.1，只本机可连"),
    RewriteItem("petrel-via", "节点上的中转标注，只用于显示链路；读出后删掉，不交给内核"),
)

/** `petrel-via` 标注出来的中转在链路底座里的说明。 */
internal const val RELAY_CAPTION = "中转 · 配置里用 petrel-via 标注"

/** 首次使用的三步，三套皮肤共用。 */
val FIRST_USE_STEPS: List<FirstUseStep> = listOf(
    FirstUseStep("导入配置", "从文件选择器挑一份 YAML，存进 App 私有目录"),
    FirstUseStep("授权 VPN", "第一次连接时系统会弹框，点「确定」"),
    FirstUseStep("批准 tailnet 登录", "复制登录链接，在电脑浏览器里批准"),
)

/** 当前出口的名字在连字符后允许折行：插零宽空格，皮肤配合 `maxLines = 2`。其余名字仍单行省略。 */
fun String.breakAfterHyphens(): String = replace("-", "-\u200B")

fun buildHome(
    s: CoreState,
    info: ConfigInfo,
    import: ImportUi,
    groups: List<ProxyGroupUi>,
    tailnet: TailnetStatusUi?,
    hostname: String,
    testing: Boolean = false,
    zone: TimeZone = TimeZone.getDefault(),
): HomeUi {
    val status = connStatus(s, info.present)
    val showChain = info.present && s.running
    val chain = if (showChain) chainOf(groups) else emptyList()
    val first = groups.firstOrNull()
    val exit = chain.lastOrNull()?.let { hop -> first?.proxies?.firstOrNull { it.name == hop.name } }
    val exitDelay = if (status == ConnStatus.NeedsLogin || exit == null) Delay.UNKNOWN else Delay(exit.delay)
    val summary = if (s.running && s.tailnet == TAILNET_RUNNING && tailnet != null) tailnetSummary(s, tailnet) else null
    val row = tailnetRow(status, summary, s.tailnetIPs.firstIPv4(), hostname)
    return HomeUi(
        status = status,
        error = s.error,
        chain = chain,
        exitDelay = exitDelay,
        groupName = if (showChain) first?.name else null,
        groupSize = if (showChain) first?.proxies?.size else null,
        chainSub = if (showChain && first != null) "${first.name} · ${groupSubtitle(first.proxies.size)}" else null,
        tailnet = summary,
        canRefresh = s.running,
        refreshing = testing,
        tailnetSub = row.sub,
        tailnetEnd = row.end,
        tailnetCard = tailnetCard(row.detail, row.end),
        config = configSummary(info, zone),
        importing = import.busy,
    )
}

fun buildNodes(s: CoreState, groups: List<ProxyGroupUi>, testing: Boolean): NodesUi = NodesUi(
    running = s.running,
    testing = testing,
    groups = groups.map { g ->
        NodeGroup(
            name = g.name,
            now = g.now,
            subtitle = groupSubtitle(g.proxies.size),
            members = g.proxies.map { p ->
                val selected = p.name == g.now
                val via = proxySubtitle(p)
                NodeItem(
                    name = p.name,
                    subtitle = listOfNotNull(via, if (selected) "当前出站" else null)
                        .joinToString(" · ").ifEmpty { null },
                    via = via,
                    delay = Delay(p.delay),
                    selected = selected,
                )
            },
        )
    },
    baseNodes = baseNodes(groups).map { name ->
        val isTs = name == "ts"
        val relays = groups.flatMap { g -> g.proxies.flatMap { it.relays } }.toSet()
        BaseNode(
            name = name,
            isTs = isTs,
            caption = when {
                isTs -> TS_CAPTION_DETAILED
                name in relays -> RELAY_CAPTION
                else -> "代理前置 · 不在任何 select 组里"
            },
            status = if (isTs) tsStatus(s.tailnet) else null,
        )
    },
)

fun buildTailnet(s: CoreState, status: TailnetStatusUi?, hostname: String): TailnetUi = when {
    !s.active -> TailnetUi.NotActive
    s.tailnet == TAILNET_NEEDS_LOGIN -> TailnetUi.NeedsLogin(s.loginURL, hostname)
    else -> TailnetUi.Running(
        state = s.tailnet,
        connected = s.tailnet == TAILNET_RUNNING,
        selfName = status?.self?.name,
        selfIp = status?.self?.ips?.firstIPv4() ?: s.tailnetIPs.firstIPv4(),
        summary = status?.let { tailnetSummary(s, it) },
        peers = status?.peers?.map(::peerUi),
    )
}

fun buildConfig(
    info: ConfigInfo,
    import: ImportUi,
    geo: GeoIpStatus?,
    geoBusy: Boolean,
    zone: TimeZone = TimeZone.getDefault(),
): ConfigUi = ConfigUi(configSummary(info, zone), import.busy, import.error, geoText(geo), geo == GeoIpStatus.Corrupt, geoBusy)

fun buildSettings(skin: Skin, skins: List<Skin>, tone: UiTone, versionName: String, info: ConfigInfo): SettingsUi =
    SettingsUi(skin, skins, tone, versionName, if (info.present) info.name else NO_CONFIG_TEXT)

/** 没有配置时的说明：设置页「配置」行的副标题、配置页的说明行。 */
internal const val NO_CONFIG_TEXT = "还没有配置"
