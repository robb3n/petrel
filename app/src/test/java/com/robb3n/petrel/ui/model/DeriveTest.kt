package com.robb3n.petrel.ui.model

import com.robb3n.petrel.ConfigInfo
import com.robb3n.petrel.ConnMode
import com.robb3n.petrel.CurrentExitIp
import com.robb3n.petrel.ExitIpInfo
import com.robb3n.petrel.ExitIpPlace
import com.robb3n.petrel.CoreState
import com.robb3n.petrel.GeoIpStatus
import com.robb3n.petrel.ImportError
import com.robb3n.petrel.ImportUi
import com.robb3n.petrel.PeerPath
import com.robb3n.petrel.ProxyGroupUi
import com.robb3n.petrel.ProxyUi
import com.robb3n.petrel.TailnetPeerUi
import com.robb3n.petrel.TailnetSelfUi
import com.robb3n.petrel.TailnetStatusUi
import com.robb3n.petrel.UiTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** 测试里本机在 tailnet 里的节点名。 */
private const val HOST = "op12-petrel"

private fun state(vpn: String = "running", tailnet: String = "Running", ips: List<String> = listOf("100.64.0.20"), error: String = "") =
    CoreState(vpn = vpn, tailnet = tailnet, tailnetIPs = ips, loginURL = "", error = error)

private fun proxy(name: String, chain: List<String> = listOf(name), delay: Int = -1, relays: List<String> = emptyList()) =
    ProxyUi(name, "ss", chain, delay, relays)

private fun group(now: String, vararg members: ProxyUi) = groupNamed("PROXY", now, *members)

private fun groupNamed(name: String, now: String, vararg members: ProxyUi) = ProxyGroupUi(name, now, "", members.toList())

private fun peer(name: String, path: PeerPath, online: Boolean = path != PeerPath.Offline, os: String = "linux", relay: String = "", ips: List<String> = listOf("fd7a::1", "100.64.0.11")) =
    TailnetPeerUi(name, ips, os, online, path, relay)

class ConnStatusTest {
    @Test fun noConfigWinsOverEverything() {
        assertEquals(ConnStatus.NoConfig, connStatus(state(), configPresent = false))
        assertEquals(ConnStatus.NoConfig, connStatus(state(vpn = "starting"), configPresent = false))
    }

    @Test fun startingIsConnecting() {
        assertEquals(ConnStatus.Connecting, connStatus(state(vpn = "starting", tailnet = "NoState"), true))
    }

    @Test fun stoppedIsOff() {
        assertEquals(ConnStatus.Off, connStatus(CoreState.STOPPED, true))
        assertEquals(ConnStatus.Off, connStatus(state(vpn = "stopped"), true))
    }

    @Test fun runningDependsOnTailnet() {
        assertEquals(ConnStatus.Connected, connStatus(state(tailnet = "Running"), true))
        assertEquals(ConnStatus.NeedsLogin, connStatus(state(tailnet = "NeedsLogin"), true))
        assertEquals(ConnStatus.TailnetOther("Starting"), connStatus(state(tailnet = "Starting"), true))
        assertEquals(ConnStatus.TailnetOther("NoState"), connStatus(state(tailnet = "NoState"), true))
    }
}

class StatusTextTest {
    @Test fun labels() {
        assertEquals("未连接", ConnStatus.NoConfig.label)
        assertEquals("未连接", ConnStatus.Off.label)
        assertEquals("连接中", ConnStatus.Connecting.label)
        assertEquals("待登录", ConnStatus.NeedsLogin.label)
        assertEquals("已连接", ConnStatus.Connected.label)
        assertEquals("已连接", ConnStatus.TailnetOther("Starting").label)
    }

    @Test fun subtitles() {
        assertEquals("还没有配置。导入一份 mihomo YAML 就能连接。", ConnStatus.NoConfig.sub("x"))
        assertEquals("hint", ConnStatus.Off.sub("hint"))
        assertEquals("正在启动 tailnet 与代理", ConnStatus.Connecting.sub("x"))
        assertEquals("登录 tailnet 之后代理才能用", ConnStatus.NeedsLogin.sub("x"))
        assertEquals("代理与 tailnet 都已就绪", ConnStatus.Connected.sub("x"))
        assertEquals("tailnet Starting", ConnStatus.TailnetOther("Starting").sub("x"))
    }
}

class DelayTest {
    @Test fun bands() {
        assertEquals(Band.Idle, Delay(-1).band)
        assertEquals(Band.Bad, Delay(0).band)
        assertEquals(Band.Ok, Delay(1).band)
        assertEquals(Band.Ok, Delay(99).band)
        assertEquals(Band.Warn, Delay(100).band)
        assertEquals(Band.Warn, Delay(199).band)
        assertEquals(Band.Bad, Delay(200).band)
        assertEquals(Band.Bad, Delay(1200).band)
    }

    @Test fun text() {
        assertEquals("—", Delay(-1).text)
        assertEquals("超时", Delay(0).text)
        assertEquals("96 ms", Delay(96).text)
        assertEquals("182 ms", Delay(182).text)
    }

    @Test fun readoutSplitsNumberAndUnit() {
        assertEquals(DelayReadout("182", "ms", timeout = false, ReadoutTone.Normal), Delay(182).readout)
        assertEquals("1" to "ms", Delay(1).readout.let { it.value to it.unit })
        assertEquals("超时" to "", Delay(0).readout.let { it.value to it.unit })
        assertEquals("—" to "", Delay(-1).readout.let { it.value to it.unit })
    }

    @Test fun readoutToneFollowsBand() {
        // spec §2.6：只有 Bad 档（≥ 200 ms 与超时）用错误色，Ok / Warn 沿用 ink，未测 Idle
        assertEquals(ReadoutTone.Normal, Delay(99).readout.tone)
        assertEquals(ReadoutTone.Normal, Delay(199).readout.tone)
        assertEquals(ReadoutTone.Bad, Delay(200).readout.tone)
        assertEquals(ReadoutTone.Bad, Delay(0).readout.tone)
        assertEquals(ReadoutTone.Idle, Delay(-1).readout.tone)
    }

    @Test fun readoutTimeoutFlagAndNumericFlag() {
        assertTrue(Delay(0).readout.timeout)
        assertFalse(Delay(0).readout.numeric)
        assertFalse(Delay(-1).readout.timeout)
        assertFalse(Delay(-1).readout.numeric)
        assertFalse(Delay(250).readout.timeout)
        assertTrue(Delay(250).readout.numeric)
    }

    @Test fun testingShowsEllipsisInIdle() {
        val testing = Delay(182).view(testing = true)
        assertEquals("…", testing.text)
        assertEquals(Band.Idle, testing.band)
        assertEquals(DelayReadout("…", "", timeout = false, ReadoutTone.Idle), testing.readout)

        val done = Delay(182).view(testing = false)
        assertEquals("182 ms", done.text)
        assertEquals(Band.Warn, done.band)
        assertEquals("182", done.readout.value)
    }
}

class ChainTest {
    private val front = "tc-u-se-ss-front"

    @Test fun threeHopsInOrderWithRoles() {
        val g = group(now = "us-lax", proxy("us-lax", listOf("ts", front, "us-lax")), proxy("tokyo", listOf("ts", front, "tokyo")))
        assertEquals(
            listOf(Hop("ts", HopRole.Tailnet), Hop(front, HopRole.Front), Hop("us-lax", HopRole.Exit)),
            chainOf(listOf(g)),
        )
    }

    @Test fun onlyOneHopIsJustTheExit() {
        val g = group(now = "direct", proxy("direct", listOf("direct")))
        assertEquals(listOf(Hop("direct", HopRole.Exit)), chainOf(listOf(g)))
    }

    @Test fun twoHopsTailnetThenExit() {
        val g = group(now = "x", proxy("x", listOf("ts", "x")))
        assertEquals(listOf(Hop("ts", HopRole.Tailnet), Hop("x", HopRole.Exit)), chainOf(listOf(g)))
    }

    @Test fun relayFromPetrelViaHasItsOwnRole() {
        val g = group(now = "tokyo", proxy("tokyo", listOf("relay", "tokyo"), relays = listOf("relay")))
        assertEquals(listOf(Hop("relay", HopRole.Relay), Hop("tokyo", HopRole.Exit)), chainOf(listOf(g)))
    }

    @Test fun relayBehindTailnetAndFront() {
        val g = group(now = "x", proxy("x", listOf("ts", "front", "relay", "x"), relays = listOf("relay")))
        assertEquals(
            listOf(Hop("ts", HopRole.Tailnet), Hop("front", HopRole.Front), Hop("relay", HopRole.Relay), Hop("x", HopRole.Exit)),
            chainOf(listOf(g)),
        )
    }

    @Test fun noGroupMeansNoChain() {
        assertTrue(chainOf(emptyList()).isEmpty())
    }

    @Test fun selectedMemberNotFoundMeansNoChain() {
        val g = group(now = "gone", proxy("a", listOf("ts", "a")))
        assertTrue(chainOf(listOf(g)).isEmpty())
    }

    @Test fun onlyFirstGroupCounts() {
        val g1 = groupNamed("A", "a", proxy("a", listOf("a")))
        val g2 = groupNamed("B", "b", proxy("b", listOf("ts", "b")))
        assertEquals(listOf(Hop("a", HopRole.Exit)), chainOf(listOf(g1, g2)))
    }
}

class NodesTest {
    @Test fun testLabelFollowsTesting() {
        assertEquals("测延迟", buildNodes(state(), emptyList(), testing = false).testLabel)
        assertEquals("测速中", buildNodes(state(), emptyList(), testing = true).testLabel)
    }

    @Test fun subtitleViaIsReversedChainWithoutSelf() {
        assertEquals("经 tc-u-se-ss-front → ts", proxySubtitle(proxy("us", listOf("ts", "tc-u-se-ss-front", "us"))))
        assertEquals("经 ts", proxySubtitle(proxy("x", listOf("ts", "x"))))
    }

    @Test fun subtitleDirectAndPlain() {
        assertEquals("直连", proxySubtitle(proxy("DIRECT", listOf("DIRECT"))))
        assertNull(proxySubtitle(proxy("plain", listOf("plain"))))
        assertNull(proxySubtitle(proxy("none", emptyList())))
    }

    @Test fun baseNodesAreChainNodesThatAreNotMembers() {
        val g = group(now = "us", proxy("us", listOf("ts", "front", "us")), proxy("jp", listOf("ts", "front", "jp")))
        assertEquals(listOf("ts", "front"), baseNodes(listOf(g)))
    }

    @Test fun relayIsABaseNodeWithItsOwnCaption() {
        val g = group(
            now = "us",
            proxy("us", listOf("ts", "front", "us")),
            proxy("tokyo", listOf("relay", "tokyo"), relays = listOf("relay")),
        )
        assertEquals("经 relay", proxySubtitle(g.proxies[1]))
        val base = buildNodes(state(), listOf(g), testing = false).baseNodes
        assertEquals(listOf("ts", "front", "relay"), base.map { it.name })
        assertEquals(RELAY_CAPTION, base[2].caption)
        assertNull(base[2].status)
        assertEquals("代理前置 · 不在任何 select 组里", base[1].caption)
    }

    @Test fun baseNodesEmptyWhenEverythingIsMember() {
        val g = group(now = "a", proxy("a"), proxy("b"))
        assertTrue(baseNodes(listOf(g)).isEmpty())
        assertTrue(baseNodes(emptyList()).isEmpty())
    }

    @Test fun nodeItemsMarkSelectedAndAppendCurrentExit() {
        val g = group(now = "us", proxy("us", listOf("ts", "front", "us"), 182), proxy("jp", listOf("ts", "front", "jp"), 96), proxy("plain", listOf("plain")))
        val ui = buildNodes(state(), listOf(g), testing = true)
        assertTrue(ui.testing)
        assertEquals("手动选择 · 3 个节点", ui.groups[0].subtitle)
        val m = ui.groups[0].members
        assertEquals("经 front → ts · 当前出站", m[0].subtitle)
        assertTrue(m[0].selected)
        assertEquals("经 front → ts", m[1].subtitle)
        assertFalse(m[1].selected)
        assertNull(m[2].subtitle)
        assertEquals(
            listOf(
                BaseNode("ts", true, "tailnet 第一跳 · Petrel 注入", NodeStatus("已连接", NodeStatusKind.Live)),
                BaseNode("front", false, "代理前置 · 不在任何 select 组里", null),
            ),
            ui.baseNodes,
        )
    }

    @Test fun tsStatusFollowsTailnetState() {
        assertEquals(NodeStatus("已连接", NodeStatusKind.Live), tsStatus("Running"))
        assertEquals(NodeStatus("待登录", NodeStatusKind.Warn), tsStatus("NeedsLogin"))
        // Starting / NoState 等不是「待登录」：显示状态名，Idle 样式
        assertEquals(NodeStatus("Starting", NodeStatusKind.Idle), tsStatus("Starting"))
        assertEquals(NodeStatus("NoState", NodeStatusKind.Idle), tsStatus("NoState"))
    }

    @Test fun baseNodeStatusUsesCurrentTailnetState() {
        val g = group(now = "us", proxy("us", listOf("ts", "front", "us")))
        fun ts(tailnet: String) = buildNodes(state(tailnet = tailnet), listOf(g), false).baseNodes.first { it.isTs }.status
        assertEquals(NodeStatus("Starting", NodeStatusKind.Idle), ts("Starting"))
        assertEquals(NodeStatus("待登录", NodeStatusKind.Warn), ts("NeedsLogin"))
    }

    @Test fun groupSubtitleIsSharedWithTheHomeChainCard() {
        assertEquals("手动选择 · 3 个节点", groupSubtitle(3))
    }

    @Test fun selectedWithoutViaIsJustCurrentExit() {
        val g = group(now = "plain", proxy("plain"))
        val m = buildNodes(state(), listOf(g), false).groups[0].members[0]
        assertEquals("当前出站", m.subtitle)
    }

    @Test fun notRunning() {
        val ui = buildNodes(CoreState.STOPPED, emptyList(), false)
        assertFalse(ui.running)
    }
}

class TailnetTest {
    private fun status(vararg peers: TailnetPeerUi) =
        TailnetStatusUi(TailnetSelfUi("op12-petrel", listOf("fd7a::9", "100.64.0.20")), peers.toList())

    @Test fun pathText() {
        assertEquals("直连" to Band.Ok, peerPath(PeerPath.Direct, ""))
        assertEquals("中继 · hkg" to Band.Warn, peerPath(PeerPath.Relay, "hkg"))
        assertEquals("中继" to Band.Warn, peerPath(PeerPath.Relay, ""))
        assertEquals("中继 · 节点" to Band.Warn, peerPath(PeerPath.PeerRelay, ""))
        assertEquals("空闲" to Band.Idle, peerPath(PeerPath.Idle, ""))
        assertEquals("离线" to Band.Idle, peerPath(PeerPath.Offline, ""))
    }

    @Test fun summaryCounts() {
        val st = status(
            peer("hu", PeerPath.Direct), peer("ks", PeerPath.Direct), peer("hmm", PeerPath.Relay, relay = "hkg"),
            peer("pr", PeerPath.PeerRelay), peer("idle", PeerPath.Idle), peer("op12", PeerPath.Offline),
        )
        val sum = tailnetSummary(state(), st)
        assertEquals("100.64.0.20", sum.ip)
        assertEquals(5, sum.online)
        assertEquals(6, sum.total)
        assertEquals(2, sum.direct)
        assertEquals(2, sum.relay)
    }

    @Test fun summaryFallsBackToStateIp() {
        val st = TailnetStatusUi(TailnetSelfUi("me", emptyList()), emptyList())
        assertEquals("100.64.0.7", tailnetSummary(state(ips = listOf("100.64.0.7")), st).ip)
    }

    @Test fun peerRowUsesIpv4AndOffline() {
        val p = peerUi(peer("op12", PeerPath.Offline, os = "android"))
        assertEquals("100.64.0.11", p.ip)
        assertTrue(p.offline)
        assertFalse(p.online)
        assertEquals(OsKind.Phone, p.os)
        assertNull(peerUi(peer("v6", PeerPath.Direct, ips = listOf("fd7a::1"))).ip)
    }

    @Test fun osMapping() {
        assertEquals(OsKind.Phone, osKind("android"))
        assertEquals(OsKind.Phone, osKind("iOS"))
        assertEquals(OsKind.Mac, osKind("macOS"))
        assertEquals(OsKind.Windows, osKind("windows"))
        assertEquals(OsKind.Server, osKind("linux"))
        assertEquals(OsKind.Server, osKind(""))
        assertEquals(OsKind.Server, osKind("freebsd"))
    }

    @Test fun pages() {
        assertEquals(TailnetUi.NotActive, buildTailnet(CoreState.STOPPED, null, HOST))
        assertEquals(TailnetUi.NeedsLogin("", HOST), buildTailnet(state(tailnet = "NeedsLogin"), null, HOST))
        val login = buildTailnet(state(tailnet = "NeedsLogin").copy(loginURL = "https://x/y"), null, HOST) as TailnetUi.NeedsLogin
        assertTrue(login.ready)
        val loading = buildTailnet(state(), null, HOST) as TailnetUi.Running
        assertTrue(loading.connected)
        assertNull(loading.selfName)
        assertNull(loading.peers)
        assertEquals("100.64.0.20", loading.selfIp)
        val starting = buildTailnet(state(vpn = "starting", tailnet = "Starting"), null, HOST) as TailnetUi.Running
        assertFalse(starting.connected)
        assertEquals("Starting", starting.state)
        assertEquals("Starting", starting.statusLabel)
        assertEquals(StatusTone.Warn, starting.statusTone)
        assertEquals("已连接", loading.statusLabel)
        assertEquals(StatusTone.Live, loading.statusTone)
        val full = buildTailnet(state(), status(peer("hu", PeerPath.Direct)), HOST) as TailnetUi.Running
        assertEquals("op12-petrel", full.selfName)
        assertEquals(1, full.peers!!.size)
        assertEquals(1, full.summary!!.direct)
    }
}

class HomeTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val cfg = ConfigInfo(present = true, name = "op12.local.yaml", timeMillis = 1_759_363_200_000L /* 2025-10-02 00:00 UTC */, imported = true)
    private val groups = listOf(
        group(now = "us", proxy("us", listOf("ts", "front", "us"), 182), proxy("jp", listOf("ts", "front", "jp"), 96)),
    )
    private val st = TailnetStatusUi(
        TailnetSelfUi("op12-petrel", listOf("100.64.0.20")),
        listOf(peer("hu", PeerPath.Direct), peer("op12", PeerPath.Offline)),
    )

    @Test fun connected() {
        val ui = buildHome(state(), cfg, ImportUi(), groups, st, HOST, zone = utc)
        assertEquals(ConnStatus.Connected, ui.status)
        assertEquals(3, ui.chain.size)
        assertEquals(Delay(182), ui.exitDelay)
        assertEquals("PROXY", ui.groupName)
        assertEquals(2, ui.groupSize)
        assertEquals("PROXY · 手动选择 · 2 个节点", ui.chainSub)
        assertEquals(1, ui.tailnet!!.online)
        assertEquals("op12-petrel · 100.64.0.20", ui.tailnetSub)
        assertEquals(TailnetEnd.Online(ui.tailnet!!), ui.tailnetEnd)
        assertEquals("10月2日", ui.config.dateShort)
        assertEquals("10月2日 00:00", ui.config.dateTime)
        assertEquals("导入", ui.config.verb)
    }

    @Test fun refreshFollowsRunningAndTesting() {
        val running = buildHome(state(), cfg, ImportUi(), groups, null, HOST, testing = true, zone = utc)
        assertTrue(running.canRefresh)
        assertTrue(running.refreshing)
        val off = buildHome(CoreState.STOPPED, cfg, ImportUi(), groups, null, HOST, zone = utc)
        assertFalse(off.canRefresh)
        assertFalse(off.refreshing)
    }

    @Test fun needsLoginKeepsChainButHidesDelayAndPeers() {
        // 带着上一段的节点数据：守卫（tailnet 必须 Running）要把它挡掉，不能显示旧的在线数
        val ui = buildHome(state(tailnet = "NeedsLogin"), cfg, ImportUi(), groups, st, HOST, zone = utc)
        assertEquals(ConnStatus.NeedsLogin, ui.status)
        assertEquals(3, ui.chain.size)
        assertEquals(Delay.UNKNOWN, ui.exitDelay)
        assertEquals(2, ui.groupSize)
        assertNull(ui.tailnet)
        assertEquals("op12-petrel · 等待批准", ui.tailnetSub)
        assertEquals(TailnetEnd.NeedsLogin, ui.tailnetEnd)
    }

    @Test fun connectingHasNoChainNoStats() {
        // 组数据还在（上一次运行留下的）：连接中不显示链路，靠的是 showChain 的 running 守卫
        val ui = buildHome(state(vpn = "starting", tailnet = "NoState", ips = emptyList()), cfg, ImportUi(), groups, st, HOST, zone = utc)
        assertEquals(ConnStatus.Connecting, ui.status)
        assertTrue(ui.chain.isEmpty())
        assertNull(ui.groupSize)
        assertNull(ui.groupName)
        assertNull(ui.chainSub)
        assertEquals("正在启动", ui.tailnetSub)
        assertEquals(TailnetEnd.None, ui.tailnetEnd)
    }

    @Test fun offAndNoConfig() {
        val off = buildHome(CoreState.STOPPED, cfg, ImportUi(), groups, null, HOST, zone = utc)
        assertEquals(ConnStatus.Off, off.status)
        assertTrue(off.chain.isEmpty())
        assertEquals(Delay.UNKNOWN, off.exitDelay)
        assertEquals("未启动", off.tailnetSub)
        val none = buildHome(CoreState.STOPPED, ConfigInfo(false, "config.yaml", 0, false), ImportUi(busy = true), emptyList(), null, HOST, zone = utc)
        assertEquals(ConnStatus.NoConfig, none.status)
        assertTrue(none.importing)
        assertEquals("", none.config.dateShort)
    }

    @Test fun runningWithoutStatusYetShowsIpOnly() {
        val ui = buildHome(state(), cfg, ImportUi(), groups, null, HOST, zone = utc)
        assertNull(ui.tailnet)
        assertEquals("op12-petrel · 100.64.0.20", ui.tailnetSub)
        assertEquals(TailnetEnd.None, ui.tailnetEnd)
        // 没有节点数据时大字是「—」、小字是 IP；不再从格式化好的副文字里抠
    }

    @Test fun runningWithoutIpFallsBackToHostname() {
        val ui = buildHome(state(ips = emptyList()), cfg, ImportUi(), groups, null, HOST, zone = utc)
        assertEquals("op12-petrel", ui.tailnetSub)
    }

    @Test fun otherTailnetStateAndError() {
        val ui = buildHome(state(tailnet = "Starting", error = "boom"), cfg, ImportUi(), groups, null, HOST, zone = utc)
        assertEquals(ConnStatus.TailnetOther("Starting"), ui.status)
        assertEquals(TailnetEnd.Other("Starting"), ui.tailnetEnd)
        assertEquals("boom", ui.error)
    }

    @Test fun exitDelayShowsEllipsisWhileRefreshing() {
        val idle = buildHome(state(), cfg, ImportUi(), groups, st, HOST, zone = utc)
        assertEquals("182 ms", idle.exitView.text)
        val refreshing = buildHome(state(), cfg, ImportUi(), groups, st, HOST, testing = true, zone = utc)
        assertEquals("…", refreshing.exitView.text)
        assertEquals(Band.Idle, refreshing.exitView.band)
        assertEquals("…", refreshing.exitView.readout.value)
        assertEquals(ReadoutTone.Idle, refreshing.exitView.readout.tone)
    }

    @Test fun hostnameComesFromInput() {
        val ui = buildHome(state(), cfg, ImportUi(), groups, st, "pjd110-petrel", zone = utc)
        assertEquals("pjd110-petrel · 100.64.0.20", ui.tailnetSub)
        val login = buildTailnet(state(tailnet = "NeedsLogin"), null, "pjd110-petrel") as TailnetUi.NeedsLogin
        assertEquals("pjd110-petrel", login.hostname)
    }

    @Test fun importLabelFollowsBusy() {
        val none = ConfigInfo(false, "config.yaml", 0, false)
        assertEquals("导入 YAML…", buildHome(CoreState.STOPPED, none, ImportUi(), emptyList(), null, HOST, zone = utc).importLabel)
        assertEquals("校验中…", buildHome(CoreState.STOPPED, none, ImportUi(busy = true), emptyList(), null, HOST, zone = utc).importLabel)
    }

    @Test fun updatedWhenNotImportedThroughUi() {
        val pushed = ConfigInfo(true, "config.yaml", 0L, false)
        assertEquals("更新", configSummary(pushed, utc).verb)
    }

    @Test fun groupWithoutSelectedMemberHasNoChainButKeepsSize() {
        val g = listOf(group(now = "gone", proxy("a", listOf("ts", "a"), 50)))
        val ui = buildHome(state(), cfg, ImportUi(), g, null, HOST, zone = utc)
        assertTrue(ui.chain.isEmpty())
        assertEquals(Delay.UNKNOWN, ui.exitDelay)
        assertEquals(1, ui.groupSize)
    }
}

private val sum56 = TailnetSummary(ip = null, online = 5, total = 6, direct = 0, relay = 0)

class EndViewTest {
    @Test fun endViews() {
        assertEquals(TailnetEndView("5 / 6 在线", EndTone.Ok), TailnetEnd.Online(sum56).view())
        assertEquals(TailnetEndView(null, EndTone.Plain), TailnetEnd.None.view())
        assertEquals(TailnetEndView("待登录", EndTone.Warn), TailnetEnd.NeedsLogin.view())
        assertEquals(TailnetEndView("Starting", EndTone.Warn), TailnetEnd.Other("Starting").view())
        assertEquals(TailnetEndView("未启用", EndTone.Plain), TailnetEnd.Disabled.view())
    }

    @Test fun summaryLabels() {
        val s = TailnetSummary("100.64.0.20", online = 5, total = 6, direct = 3, relay = 1)
        assertEquals("5/6", s.ratio)
        assertEquals("5 / 6", s.ratioSpaced)
        assertEquals("5 / 6 在线", s.onlineLabel)
        assertEquals("直连 3 · 中继 1", s.pathLabel)
    }
}

class ConnToneTest {
    @Test fun toneMapping() {
        assertEquals(StatusTone.Idle, ConnStatus.NoConfig.tone)
        assertEquals(StatusTone.Idle, ConnStatus.Off.tone)
        assertEquals(StatusTone.Busy, ConnStatus.Connecting.tone)
        assertEquals(StatusTone.Warn, ConnStatus.NeedsLogin.tone)
        assertEquals(StatusTone.Warn, ConnStatus.TailnetOther("Starting").tone)
        assertEquals(StatusTone.Live, ConnStatus.Connected.tone)
    }
}

class CaptionTest {
    @Test fun hopCaptionsPerSkinWording() {
        assertEquals("tailnet 第一跳", HopRole.Tailnet.caption("PROXY"))
        assertEquals("tailnet 第一跳 · Petrel 注入", HopRole.Tailnet.caption("PROXY", detailed = true))
        assertEquals("代理前置", HopRole.Front.caption("PROXY"))
        assertEquals("代理前置", HopRole.Front.caption("PROXY", detailed = true))
        assertEquals("出口 · PROXY", HopRole.Exit.caption("PROXY"))
        assertEquals("出口 · PROXY 当前选中", HopRole.Exit.caption("PROXY", detailed = true))
    }

    @Test fun breakAfterHyphensInsertsZeroWidthSpace() {
        assertEquals("hk-\u200Bhkt-\u200Bpremium-\u200B01-\u200Bdialer", "hk-hkt-premium-01-dialer".breakAfterHyphens())
        assertEquals("tokyo", "tokyo".breakAfterHyphens())
    }
}

class ConfigUiTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val info = ConfigInfo(present = true, name = "op12.local.yaml", timeMillis = 1_759_363_200_000L, imported = true)

    @Test fun timeLine() {
        assertEquals("10月2日 00:00 导入", buildConfig(info, ImportUi(), null, false, utc).timeLine)
        assertEquals(
            "10月2日 00:00 导入 · 仍在使用",
            buildConfig(info, ImportUi(error = ImportError("x")), null, false, utc).timeLine,
        )
        assertEquals("还没有配置", buildConfig(ConfigInfo(false, "config.yaml", 0, false), ImportUi(), null, false, utc).timeLine)
    }

    @Test fun stamps() {
        val s = configSummary(info, utc)
        assertEquals("10月2日导入", s.stampShort)
        assertEquals("10月2日 00:00 导入", s.stampFull)
        // 没有配置时没有日期，也不能剩下孤零零的「更新」
        val none = configSummary(ConfigInfo(false, "config.yaml", 0, false), utc)
        assertEquals("", none.stampShort)
        assertEquals("", none.stampFull)
    }

    @Test fun verifiedAndStillInUseAreExclusive() {
        assertTrue(buildConfig(info, ImportUi(), null, false, utc).verified)
        val failed = buildConfig(info, ImportUi(error = ImportError("x")), null, false, utc)
        assertTrue(failed.stillInUse)
        assertFalse(failed.verified)
        assertFalse(buildConfig(info.copy(imported = false), ImportUi(), null, false, utc).verified)
    }

    @Test fun buttonLabels() {
        assertEquals("导入 YAML…", buildConfig(info, ImportUi(), null, false, utc).importLabel)
        assertEquals("校验中…", buildConfig(info, ImportUi(busy = true), null, false, utc).importLabel)
        assertEquals("替换…", buildConfig(info, ImportUi(), null, false, utc).geoButtonLabel)
        assertEquals("校验中…", buildConfig(info, ImportUi(), null, true, utc).geoButtonLabel)
    }

    @Test fun settingsConfigRowWithoutConfig() {
        val none = ConfigInfo(false, "config.yaml", 0, false)
        assertEquals("还没有配置", buildSettings(ConnMode.Both, UiTone.Auto, ExitIpPlace.Card, "0.1.0", none).configName)
        assertEquals("op12.local.yaml", buildSettings(ConnMode.Both, UiTone.Auto, ExitIpPlace.Card, "0.1.0", info).configName)
    }

    @Test fun geoText() {
        assertEquals("已就绪", buildConfig(info, ImportUi(), GeoIpStatus.Ready, false, utc).geoText)
        assertEquals("未找到", buildConfig(info, ImportUi(), GeoIpStatus.Missing, false, utc).geoText)
        assertEquals("—", buildConfig(info, ImportUi(), null, false, utc).geoText)
        val bad = buildConfig(info, ImportUi(), GeoIpStatus.Corrupt, false, utc)
        assertEquals("文件损坏", bad.geoText)
        assertTrue(bad.geoBad)
        assertFalse(buildConfig(info, ImportUi(), GeoIpStatus.Ready, false, utc).geoBad)
    }

    @Test fun rewritesMentionEveryControllerEntryRemoval() {
        val rows = buildConfig(info, ImportUi(), null, false, utc).rewrites
        assertEquals(
            listOf("DNS 与国内分流", "ts", "tun", "external-controller", "interface-name · routing-mark", "listeners · tunnels", "petrel-via"),
            rows.map { it.key },
        )
        assertTrue(rows.first { it.key == "external-controller" }.desc.contains("其余 controller 入口一律删除"))
    }

    @Test fun firstUseHasThreeSteps() {
        assertEquals(listOf("导入配置", "授权 VPN", "批准 tailnet 登录"), FIRST_USE_STEPS.map { it.title })
    }
}
