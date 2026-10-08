package com.robb3n.petrel.ui.model

import com.robb3n.petrel.ConfigInfo
import com.robb3n.petrel.ConnMode
import com.robb3n.petrel.CoreState
import com.robb3n.petrel.CurrentExitIp
import com.robb3n.petrel.ExitIpInfo
import com.robb3n.petrel.ExitIpPlace
import com.robb3n.petrel.GeoIpStatus
import com.robb3n.petrel.ImportUi
import com.robb3n.petrel.PeerPath
import com.robb3n.petrel.ProxyGroupUi
import com.robb3n.petrel.ProxyUi
import com.robb3n.petrel.TAILNET_NEEDS_LOGIN
import com.robb3n.petrel.TAILNET_RUNNING
import com.robb3n.petrel.TailnetPeerUi
import com.robb3n.petrel.TailnetStatusUi
import com.robb3n.petrel.UiTone
import com.robb3n.petrel.TS_NODE
import com.robb3n.petrel.firstIPv4
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * 把 CoreState / repository 的数据推导成各屏的模型。纯函数、无 Android 依赖，JVM 单测见 app/src/test。
 */

/**
 * [mode] 是本次（或将要）连接的模式：仅 tailnet 不需要配置。[exitBlocked]：仅代理模式下当前出口经过 `ts`。
 */
fun connStatus(s: CoreState, configPresent: Boolean, mode: ConnMode = ConnMode.Both, exitBlocked: Boolean = false): ConnStatus = when {
    !configPresent && mode != ConnMode.Tailnet -> ConnStatus.NoConfig
    s.vpn == "starting" -> ConnStatus.Connecting
    !s.running -> ConnStatus.Off
    mode == ConnMode.Proxy -> if (exitBlocked) ConnStatus.ExitNeedsTailnet else ConnStatus.Connected
    s.tailnet == TAILNET_RUNNING -> ConnStatus.Connected
    s.tailnet == TAILNET_NEEDS_LOGIN -> ConnStatus.NeedsLogin
    else -> ConnStatus.TailnetOther(s.tailnet)
}

/**
 * 当前链路：第一个组当前选中成员的 chain（Go 层给的就是「第一跳 → … → 成员自己」，先 ts、再前置、最后出口）。
 * 最后一跳是出口，名字是 `ts` 的是 tailnet 第一跳，成员 relays 里的是中转，其余是代理前置。只有一跳时只有出口；没有组或找不到选中成员时为空。
 * [tailnetOn] 为 false（仅代理）时 `ts` 那一跳标成 blocked。
 */
fun chainOf(groups: List<ProxyGroupUi>, tailnetOn: Boolean = true): List<Hop> {
    val member = currentExit(groups) ?: return emptyList()
    val last = member.chain.lastIndex
    return member.chain.mapIndexed { i, name ->
        val role = when {
            i == last -> HopRole.Exit
            name == TS_NODE -> HopRole.Tailnet
            name in member.relays -> HopRole.Relay
            else -> HopRole.Front
        }
        Hop(name, role, blocked = !tailnetOn && name == TS_NODE)
    }
}

/** 第一个组当前选中的成员。 */
fun currentExit(groups: List<ProxyGroupUi>): ProxyUi? {
    val g = groups.firstOrNull() ?: return null
    return g.proxies.firstOrNull { it.name == g.now }
}

/** 去掉空串与紧挨着的重复（「新加坡 · 新加坡」）。 */
private fun joinPlaces(parts: List<String>): String =
    parts.map { it.trim() }.filter { it.isNotEmpty() }.fold(listOf<String>()) { acc, p -> if (acc.lastOrNull() == p) acc else acc + p }
        .joinToString(" · ")

/** 出口 IP 的各种写法，见 [IpView]。 */
fun ipView(info: ExitIpInfo): IpView {
    val place = info.city.ifBlank { info.region }.ifBlank { info.country }
    val ispShort = info.isp.substringBefore(',').trim()
    return IpView(
        ip = info.ip,
        cc = info.countryCode.ifBlank { "?" },
        short = joinPlaces(listOf(info.country, place)),
        location = joinPlaces(listOf(info.country, info.region, info.city)),
        isp = listOf(info.isp, info.asn).filter { it.isNotBlank() }.joinToString(" · "),
        kind = when {
            info.hosting -> IpKind.Hosting
            info.mobile -> IpKind.Mobile
            else -> IpKind.Residential
        },
        line = listOf(info.ip, place, ispShort).filter { it.isNotBlank() }.joinToString(" · "),
    )
}

/** 连接页的出口 IP，见 [HomeUi.exitIp]。 */
internal fun exitIpUi(status: ConnStatus, current: CurrentExitIp?): ExitIpUi? = when {
    status == ConnStatus.ExitNeedsTailnet -> ExitIpUi.Unavailable(EXIT_BLOCKED_HINT)
    status != ConnStatus.Connected -> null
    current == null -> null
    current.info != null -> ExitIpUi.Ready(ipView(current.info), refreshing = current.loading)
    current.loading -> ExitIpUi.Loading
    else -> ExitIpUi.Unavailable(EXIT_FAILED_HINT)
}

internal const val EXIT_BLOCKED_HINT = "当前出口要经过 tailnet，换一个不经过的出口"
internal const val EXIT_FAILED_HINT = "点右上角刷新重试"

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
)

/** 连接页 tailnet 行：副文字（「pjd110-petrel · 100.64.0.20」）与右侧值。 */
internal data class TailnetRow(val sub: String, val end: TailnetEnd)

/** 连接页 tailnet 行的副文字与右侧值，见 spec §2.7「tailnet 摘要」。[hostname] 是本机在 tailnet 里的节点名。 */
internal fun tailnetRow(status: ConnStatus, summary: TailnetSummary?, selfIp: String?, hostname: String, mode: ConnMode = ConnMode.Both): TailnetRow {
    if (!mode.tailnetOn) return TailnetRow("仅代理模式下未启用", TailnetEnd.Disabled)
    fun withHost(ip: String?) = TailnetRow(if (ip != null) "$hostname · $ip" else hostname, TailnetEnd.None)
    return when (status) {
        ConnStatus.Connected ->
            withHost(summary?.ip ?: selfIp).copy(end = summary?.let { TailnetEnd.Online(it) } ?: TailnetEnd.None)
        ConnStatus.NeedsLogin -> TailnetRow("$hostname · 等待批准", TailnetEnd.NeedsLogin)
        is ConnStatus.TailnetOther -> withHost(summary?.ip ?: selfIp).copy(end = TailnetEnd.Other(status.state))
        ConnStatus.Connecting -> TailnetRow("正在启动", TailnetEnd.None)
        ConnStatus.Off, ConnStatus.NoConfig, ConnStatus.ExitNeedsTailnet -> TailnetRow("未启动", TailnetEnd.None)
    }
}

/** 状态卡的统计格，随模式变：tailnet + 代理「出口延迟 / tailnet 在线 / 可选出口」，仅 tailnet「在线 / 直连 / 中继」，仅代理「出口延迟 / 可选出口」。 */
internal fun homeStats(
    mode: ConnMode,
    exit: DelayView,
    summary: TailnetSummary?,
    group: ProxyGroupUi?,
    running: Boolean,
): List<StatUi> {
    val d = exit.readout
    val delay = StatUi(d.value, d.unit, "出口延迟", StatSlot.S0, Tab.Nodes)
    val size = group?.proxies?.size?.takeIf { running }
    return when (mode) {
        ConnMode.Both -> listOf(
            delay,
            StatUi(summary?.ratio ?: "—", "", "tailnet 在线", StatSlot.S3, Tab.Tailnet),
            StatUi(size?.toString() ?: "—", "", "可选出口", StatSlot.S2, Tab.Nodes),
        )
        ConnMode.Tailnet -> listOf(
            StatUi(summary?.ratio ?: "—", "", "tailnet 在线", StatSlot.S3, Tab.Tailnet),
            StatUi(summary?.direct?.toString() ?: "—", "", "直连", StatSlot.S0, Tab.Tailnet),
            StatUi(summary?.relay?.toString() ?: "—", "", "中继", StatSlot.S2, Tab.Tailnet),
        )
        ConnMode.Proxy -> {
            val blocked = group?.proxies?.count { it.viaTailnet } ?: 0
            if (size != null && blocked > 0) {
                listOf(delay, StatUi("${size - blocked}/$size", "", "可用出口", StatSlot.S2, Tab.Nodes))
            } else {
                listOf(delay, StatUi(size?.toString() ?: "—", "", "可选出口", StatSlot.S2, Tab.Nodes))
            }
        }
    }
}

/** 组的说明：「手动选择 · N 个节点」。节点页的组卡头与连接页的链路卡共用。 */
fun groupSubtitle(size: Int): String = "手动选择 · $size 个节点"

/** 底座节点 `ts` 的状态：只有 Running / NeedsLogin 有专门文案，其余显示 tailnet 状态原文。 */
fun tsStatus(tailnetState: String, tailnetOn: Boolean = true): NodeStatus = if (!tailnetOn) NodeStatus("未启用", NodeStatusKind.Idle) else when (tailnetState) {
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
    RewriteItem("DNS 与国内分流", "默认自动应用：国内域名直连解析，其他域名的加密 DNS 跟随主代理；切换节点刷新解析缓存。原始文件保留。"),
    RewriteItem("ts", "注入的 tailnet 节点，用 dialer-proxy: ts 引用"),
    RewriteItem("tun", "接管为 Petrel 的 VPN，固定 gvisor"),
    RewriteItem("external-controller", "固定 127.0.0.1:9090，没写 secret 时随机生成；其余 controller 入口一律删除"),
    RewriteItem("interface-name · routing-mark", "删除（桌面端残留），顶层与节点上的都删"),
    RewriteItem("listeners · tunnels", "入站保留，监听地址改成 127.0.0.1，只本机可连"),
    RewriteItem("petrel-via", "节点上的中转标注，只用于显示链路；读出后删掉，不交给内核"),
)

/** `petrel-via` 标注出来的中转在链路底座里的说明。 */
internal const val RELAY_CAPTION = "中转 · 配置里用 petrel-via 标注"

/** 首次使用的三步。 */
val FIRST_USE_STEPS: List<FirstUseStep> = listOf(
    FirstUseStep("导入配置", "从文件选择器挑一份 YAML，存进 App 私有目录"),
    FirstUseStep("授权 VPN", "第一次连接时系统会弹框，点「确定」"),
    FirstUseStep("批准 tailnet 登录", "复制登录链接，在电脑浏览器里批准"),
)

/** 当前出口的名字在连字符后允许折行：插零宽空格，皮肤配合 `maxLines = 2`。其余名字仍单行省略。 */
fun String.breakAfterHyphens(): String = replace("-", "-\u200B")

/**
 * [prefMode] 是设置里的连接模式：VPN 没在跑时按它显示（仅 tailnet 不需要配置）；在跑时以内核报的 [CoreState.mode] 为准。
 * [exitIp] 是当前出口的出口 IP 查询（[com.robb3n.petrel.ExitIpRepository.current]），[place] 是设置里的摆法。
 */
fun buildHome(
    s: CoreState,
    info: ConfigInfo,
    import: ImportUi,
    groups: List<ProxyGroupUi>,
    tailnet: TailnetStatusUi?,
    hostname: String,
    testing: Boolean = false,
    zone: TimeZone = TimeZone.getDefault(),
    prefMode: ConnMode = ConnMode.Both,
    exitIp: CurrentExitIp? = null,
    place: ExitIpPlace = ExitIpPlace.Card,
): HomeUi {
    val mode = s.mode ?: prefMode
    val first = groups.firstOrNull()
    val exit = currentExit(groups)
    val showChain = info.present && s.running && mode.proxyOn
    val exitBlocked = mode == ConnMode.Proxy && exit?.viaTailnet == true
    val status = connStatus(s, info.present, mode, exitBlocked)
    val chain = if (showChain) chainOf(groups, mode.tailnetOn) else emptyList()
    val exitDelay = if (status == ConnStatus.NeedsLogin || !showChain || exit == null) Delay.UNKNOWN else Delay(exit.delay)
    val summary = if (s.running && mode.tailnetOn && s.tailnet == TAILNET_RUNNING && tailnet != null) tailnetSummary(s, tailnet) else null
    val row = tailnetRow(status, summary, s.tailnetIPs.firstIPv4(), hostname, mode)
    val exitView = if (exitBlocked) UNAVAILABLE_DELAY else exitDelay.view(testing)
    return HomeUi(
        status = status,
        error = s.error,
        chain = chain,
        exitDelay = exitDelay,
        mode = mode,
        statusSub = status.sub(OFF_HINT, mode),
        stats = if (status == ConnStatus.NoConfig) emptyList() else homeStats(mode, exitView, summary, if (showChain) first else null, s.running),
        exitIp = exitIpUi(status, exitIp),
        exitIpPlace = if (place == ExitIpPlace.Chain && chain.isEmpty()) ExitIpPlace.Card else place,
        exitIpDirect = mode == ConnMode.Tailnet,
        groupName = if (showChain) first?.name else null,
        groupSize = if (showChain) first?.proxies?.size else null,
        chainSub = if (showChain && first != null) "${first.name} · ${groupSubtitle(first.proxies.size)}" else null,
        tailnet = summary,
        canRefresh = s.running,
        refreshing = testing,
        tailnetSub = row.sub,
        tailnetEnd = row.end,
        config = configSummary(info, zone),
        importing = import.busy,
    )
}

/** 节点行的出口 IP：有服务器地址的成员才记；没记下时提示测延迟补上，补查中写「查询出口 IP…」。 */
internal fun nodeIp(p: ProxyUi, cache: Map<String, ExitIpInfo>, filling: Boolean, blocked: Boolean): NodeIp {
    val key = p.ipKey ?: return NodeIp.None
    cache[key]?.let { return NodeIp.Known(ipView(it)) }
    return NodeIp.Missing(
        when {
            blocked -> "还没记下出口 IP"
            filling -> "查询出口 IP…"
            else -> "还没记下出口 IP，测延迟时补上"
        },
    )
}

fun buildNodes(
    s: CoreState,
    groups: List<ProxyGroupUi>,
    testing: Boolean,
    ipCache: Map<String, ExitIpInfo> = emptyMap(),
    filling: Boolean = false,
): NodesUi {
    val tailnetOn = s.mode != ConnMode.Proxy
    return NodesUi(
        running = s.running,
        testing = testing,
        groups = groups.map { g ->
            NodeGroup(
                name = g.name,
                now = g.now,
                subtitle = groupSubtitle(g.proxies.size),
                members = g.proxies.map { p ->
                    val selected = p.name == g.now
                    val blocked = !tailnetOn && p.viaTailnet
                    NodeItem(
                        name = p.name,
                        subtitle = listOfNotNull(
                            proxySubtitle(p),
                            if (blocked) "仅代理模式下不可用" else if (selected) "当前出站" else null,
                        ).joinToString(" · ").ifEmpty { null },
                        delay = Delay(p.delay),
                        selected = selected,
                        blocked = blocked,
                        ip = nodeIp(p, ipCache, filling, blocked),
                    )
                },
            )
        },
        baseNodes = baseNodes(groups).map { name ->
            val isTs = name == TS_NODE
            val relays = groups.flatMap { g -> g.proxies.flatMap { it.relays } }.toSet()
            BaseNode(
                name = name,
                isTs = isTs,
                caption = when {
                    isTs && !tailnetOn -> TS_CAPTION_DISABLED
                    isTs -> TS_CAPTION_DETAILED
                    name in relays -> RELAY_CAPTION
                    else -> "代理前置 · 不在任何 select 组里"
                },
                status = if (isTs) tsStatus(s.tailnet, tailnetOn) else null,
                disabled = isTs && !tailnetOn,
            )
        },
    )
}

fun buildTailnet(s: CoreState, status: TailnetStatusUi?, hostname: String): TailnetUi = when {
    !s.active -> TailnetUi.NotActive
    s.mode == ConnMode.Proxy -> TailnetUi.Disabled
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

fun buildSettings(mode: ConnMode, tone: UiTone, place: ExitIpPlace, versionName: String, info: ConfigInfo): SettingsUi =
    SettingsUi(mode, tone, place, versionName, if (info.present) info.name else NO_CONFIG_TEXT)

/** 没有配置时的说明：设置页「配置」行的副标题、配置页的说明行。 */
internal const val NO_CONFIG_TEXT = "还没有配置"
