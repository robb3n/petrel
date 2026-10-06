package com.robb3n.petrel.ui.model

import com.robb3n.petrel.ConfigInfo
import com.robb3n.petrel.ConnMode
import com.robb3n.petrel.CoreState
import com.robb3n.petrel.CurrentExitIp
import com.robb3n.petrel.ExitIpInfo
import com.robb3n.petrel.ExitIpPlace
import com.robb3n.petrel.ImportUi
import com.robb3n.petrel.ProxyGroupUi
import com.robb3n.petrel.ProxyUi
import com.robb3n.petrel.TailnetSelfUi
import com.robb3n.petrel.TailnetStatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

private val utc = TimeZone.getTimeZone("UTC")
private val cfg = ConfigInfo(true, "op12.local.yaml", 0L, true)
private val noCfg = ConfigInfo(false, "config.yaml", 0L, false)

private fun running(mode: ConnMode, tailnet: String = if (mode == ConnMode.Proxy) "Disabled" else "Running") =
    CoreState("running", tailnet, listOf("100.64.0.20"), "", "", mode = mode)

private val viaTs = ProxyUi("us", "ss", listOf("ts", "front", "us"), 182, addr = "1.2.3.4:443")
private val direct = ProxyUi("jp", "ss", listOf("jp"), 64, addr = "5.6.7.8:443")
private val groups = listOf(ProxyGroupUi("PROXY", "us", "", listOf(viaTs, direct)))
private val groupsJp = listOf(ProxyGroupUi("PROXY", "jp", "", listOf(viaTs, direct)))
private val st = TailnetStatusUi(TailnetSelfUi("op12-petrel", listOf("100.64.0.20")), emptyList())

private val la = ExitIpInfo("203.0.113.42", "US", "美国", "加利福尼亚州", "洛杉矶", "NTT America, Inc.", "AS2914", hosting = true, mobile = false)

private fun home(
    s: CoreState,
    g: List<ProxyGroupUi> = groups,
    info: ConfigInfo = cfg,
    prefMode: ConnMode = ConnMode.Both,
    ip: CurrentExitIp? = null,
    place: ExitIpPlace = ExitIpPlace.Card,
) = buildHome(s, info, ImportUi(), g, st, "op12-petrel", zone = utc, prefMode = prefMode, exitIp = ip, place = place)

class ConnModeTest {
    @Test fun switchesToMode() {
        assertEquals(ConnMode.Both, ConnMode.of(tailnet = true, proxy = true))
        assertEquals(ConnMode.Tailnet, ConnMode.of(tailnet = true, proxy = false))
        assertEquals(ConnMode.Proxy, ConnMode.of(tailnet = false, proxy = true))
        // 两个都关不允许，按默认处理
        assertEquals(ConnMode.Both, ConnMode.of(tailnet = false, proxy = false))
        assertNull(ConnMode.fromKey(""))
        assertEquals(ConnMode.Proxy, ConnMode.fromKey("proxy"))
    }
}

class ModeStatusTest {
    @Test fun tailnetOnlyNeedsNoConfig() {
        assertEquals(ConnStatus.Off, connStatus(CoreState.STOPPED, configPresent = false, mode = ConnMode.Tailnet))
        assertEquals(ConnStatus.NoConfig, connStatus(CoreState.STOPPED, configPresent = false, mode = ConnMode.Proxy))
    }

    @Test fun proxyOnlyIgnoresTailnetState() {
        assertEquals(ConnStatus.Connected, connStatus(running(ConnMode.Proxy), true, ConnMode.Proxy))
        assertEquals(ConnStatus.ExitNeedsTailnet, connStatus(running(ConnMode.Proxy), true, ConnMode.Proxy, exitBlocked = true))
    }

    @Test fun subtitlesFollowMode() {
        assertEquals("仅 tailnet · 其它流量直接走手机网络", ConnStatus.Connected.sub(mode = ConnMode.Tailnet))
        assertEquals("仅代理 · tailnet 未启用", ConnStatus.Connected.sub(mode = ConnMode.Proxy))
        assertEquals("正在启动代理", ConnStatus.Connecting.sub(mode = ConnMode.Proxy))
        assertEquals("登录之后才能访问 tailnet", ConnStatus.NeedsLogin.sub(mode = ConnMode.Tailnet))
        assertEquals("出口不可用", ConnStatus.ExitNeedsTailnet.tagLabel)
        assertEquals("已连接", ConnStatus.ExitNeedsTailnet.label)
        assertEquals(StatusTone.Warn, ConnStatus.ExitNeedsTailnet.tone)
    }
}

class ModeHomeTest {
    @Test fun bothKeepsTheThreeStats() {
        val ui = home(running(ConnMode.Both))
        assertEquals(listOf("出口延迟", "tailnet 在线", "可选出口"), ui.stats.map { it.key })
        assertEquals(listOf("182", "0/0", "2"), ui.stats.map { it.value })
        assertEquals(Tab.Tailnet, ui.stats[1].target)
        assertFalse(ui.tailnetDisabled)
        assertFalse(ui.proxyDisabled)
    }

    @Test fun tailnetOnlyHasNoChainAndTailnetStats() {
        val ui = home(running(ConnMode.Tailnet), g = emptyList())
        assertEquals(ConnStatus.Connected, ui.status)
        assertTrue(ui.chain.isEmpty())
        assertEquals(listOf("tailnet 在线", "直连", "中继"), ui.stats.map { it.key })
        assertTrue(ui.proxyDisabled)
        assertEquals("仅 tailnet · 其它流量直接走手机网络", ui.statusSub)
        assertTrue(ui.exitIpDirect)
    }

    @Test fun proxyOnlyWithDirectExit() {
        val ui = home(running(ConnMode.Proxy), g = groupsJp)
        assertEquals(ConnStatus.Connected, ui.status)
        assertEquals(listOf(Hop("jp", HopRole.Exit)), ui.chain)
        assertEquals(listOf("出口延迟", "可用出口"), ui.stats.map { it.key })
        assertEquals("1/2", ui.stats[1].value)
        assertTrue(ui.tailnetDisabled)
        assertEquals("仅代理模式下未启用", ui.tailnetSub)
        assertEquals(TailnetEnd.Disabled, ui.tailnetEnd)
        assertNull(ui.tailnet)
    }

    @Test fun proxyOnlyWithExitThroughTs() {
        val ui = home(running(ConnMode.Proxy))
        assertEquals(ConnStatus.ExitNeedsTailnet, ui.status)
        assertTrue(ui.chain.first { it.name == "ts" }.blocked)
        assertEquals("tailnet 第一跳 · 仅代理模式下未启用", ui.chain.first().caption("PROXY"))
        assertEquals("不可用", ui.exitView.text)
        assertEquals("—", ui.stats[0].value)
        assertEquals(ExitIpUi.Unavailable(EXIT_BLOCKED_HINT), ui.exitIp)
    }

    @Test fun stoppedFollowsThePreferenceMode() {
        val ui = home(CoreState.STOPPED, info = noCfg, prefMode = ConnMode.Tailnet)
        assertEquals(ConnStatus.Off, ui.status)
        assertEquals(ConnMode.Tailnet, ui.mode)
        assertEquals(listOf("tailnet 在线", "直连", "中继"), ui.stats.map { it.key })
    }

    @Test fun runningModeWinsOverPreference() {
        // 改了设置、服务还没重启完：显示内核实际在跑的模式
        val ui = home(running(ConnMode.Both), prefMode = ConnMode.Proxy)
        assertEquals(ConnMode.Both, ui.mode)
    }

    @Test fun chainPlaceFallsBackToCardWithoutChain() {
        assertEquals(ExitIpPlace.Card, home(running(ConnMode.Tailnet), g = emptyList(), place = ExitIpPlace.Chain).exitIpPlace)
        assertEquals(ExitIpPlace.Chain, home(running(ConnMode.Both), place = ExitIpPlace.Chain).exitIpPlace)
        assertEquals(ExitIpPlace.Hero, home(running(ConnMode.Both), place = ExitIpPlace.Hero).exitIpPlace)
    }
}

class ExitIpUiTest {
    @Test fun ipViewWritings() {
        val v = ipView(la)
        assertEquals("US", v.cc)
        assertEquals("美国 · 洛杉矶", v.short)
        assertEquals("美国 · 加利福尼亚州 · 洛杉矶", v.location)
        assertEquals("NTT America, Inc. · AS2914", v.isp)
        assertEquals(IpKind.Hosting, v.kind)
        assertEquals("203.0.113.42 · 洛杉矶 · NTT America", v.line)
    }

    @Test fun repeatedPlacesCollapse() {
        val sg = ExitIpInfo("192.0.2.140", "SG", "新加坡", "新加坡", "新加坡", "Singtel", "", hosting = false, mobile = false)
        val v = ipView(sg)
        assertEquals("新加坡", v.short)
        assertEquals("新加坡", v.location)
        assertEquals("Singtel", v.isp)
        assertEquals(IpKind.Residential, v.kind)
        assertEquals(IpKind.Mobile, ipView(sg.copy(mobile = true)).kind)
        assertEquals("?", ipView(sg.copy(countryCode = "")).cc)
    }

    @Test fun stateMapping() {
        val c = ConnStatus.Connected
        assertNull(exitIpUi(c, null))
        assertNull(exitIpUi(ConnStatus.NeedsLogin, CurrentExitIp(la, false, false)))
        assertNull(exitIpUi(ConnStatus.Off, CurrentExitIp(la, false, false)))
        assertEquals(ExitIpUi.Loading, exitIpUi(c, CurrentExitIp(null, loading = true, failed = false)))
        assertEquals(ExitIpUi.Ready(ipView(la), refreshing = true), exitIpUi(c, CurrentExitIp(la, loading = true, failed = false)))
        assertEquals(ExitIpUi.Ready(ipView(la), refreshing = false), exitIpUi(c, CurrentExitIp(la, loading = false, failed = false)))
        assertEquals(ExitIpUi.Unavailable(EXIT_FAILED_HINT), exitIpUi(c, CurrentExitIp(null, loading = false, failed = true)))
    }
}

class ModeNodesTest {
    private val cache = mapOf("jp|5.6.7.8:443" to la)

    @Test fun nodeIpsFromCache() {
        val ui = buildNodes(running(ConnMode.Both), groupsJp, testing = false, ipCache = cache)
        val (us, jp) = ui.groups[0].members
        assertEquals(NodeIp.Known(ipView(la)), jp.ip)
        assertEquals(NodeIp.Missing("还没记下出口 IP，测延迟时补上"), us.ip)
        val filling = buildNodes(running(ConnMode.Both), groupsJp, testing = false, ipCache = cache, filling = true)
        assertEquals(NodeIp.Missing("查询出口 IP…"), filling.groups[0].members[0].ip)
    }

    @Test fun membersWithoutAddressAreNotRecorded() {
        val g = listOf(ProxyGroupUi("PROXY", "DIRECT", "", listOf(ProxyUi("DIRECT", "Direct", listOf("DIRECT"), -1))))
        assertEquals(NodeIp.None, buildNodes(running(ConnMode.Both), g, false).groups[0].members[0].ip)
    }

    @Test fun addressChangeInvalidatesTheRecord() {
        val moved = listOf(ProxyGroupUi("PROXY", "jp", "", listOf(direct.copy(addr = "9.9.9.9:443"))))
        assertTrue(buildNodes(running(ConnMode.Both), moved, false, ipCache = cache).groups[0].members[0].ip is NodeIp.Missing)
    }

    @Test fun proxyOnlyBlocksNodesThroughTs() {
        val ui = buildNodes(running(ConnMode.Proxy), groupsJp, testing = false, ipCache = cache)
        val (us, jp) = ui.groups[0].members
        assertTrue(us.blocked)
        assertEquals("经 front → ts · 仅代理模式下不可用", us.subtitle)
        assertEquals("不可用", us.delayView(testing = true).text)
        assertEquals(NodeIp.Missing("还没记下出口 IP"), us.ip)
        assertFalse(jp.blocked)
        assertEquals("当前出站", jp.subtitle)
        val ts = ui.baseNodes.first { it.isTs }
        assertTrue(ts.disabled)
        assertEquals(NodeStatus("未启用", NodeStatusKind.Idle), ts.status)
        assertEquals("tailnet 第一跳 · 仅代理模式下未启用", ts.caption)
    }

    @Test fun tailnetPageIsDisabledInProxyOnly() {
        assertEquals(TailnetUi.Disabled, buildTailnet(running(ConnMode.Proxy), null, "op12-petrel"))
    }
}
