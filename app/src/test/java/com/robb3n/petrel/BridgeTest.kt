package com.robb3n.petrel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactStateJsonTest {
    @Test fun loginUrlAndErrorAreRedacted() {
        val json = """{"vpn":"stopped","tailnet":"Stopped","tailnetIPs":[],""" +
            """"loginURL":"https://login.tailscale.com/a/abc","error":"parse config: yaml: line 3: password: \"s3cret\"","exit":"","groupsRev":0}"""
        val out = redactStateJson(json)
        assertFalse(out.contains("abc"))
        assertFalse(out.contains("s3cret"))
        assertFalse(out.contains("password"))
        assertEquals(
            """{"vpn":"stopped","tailnet":"Stopped","tailnetIPs":[],"loginURL":"<set>","error":"<set>","exit":"","groupsRev":0}""",
            out,
        )
    }

    @Test fun emptyFieldsStayEmpty() {
        val json = """{"vpn":"running","loginURL":"","error":"","exit":""}"""
        assertEquals(json, redactStateJson(json))
    }

    @Test fun exitNodeNameIsRedacted() {
        val json = """{"vpn":"running","tailnet":"Running","tailnetIPs":["100.64.0.20"],"loginURL":"","error":"",""" +
            """"exit":"东京 \"IIJ\" 01","groupsRev":3,"mode":"both"}"""
        val out = redactStateJson(json)
        assertFalse(out.contains("IIJ") || out.contains("东京"))
        assertEquals(
            """{"vpn":"running","tailnet":"Running","tailnetIPs":["100.64.0.20"],"loginURL":"","error":"",""" +
                """"exit":"<set>","groupsRev":3,"mode":"both"}""",
            out,
        )
    }
}

class TailnetHostnameTest {
    @Test fun modelIsNormalized() {
        assertEquals("pjd110-petrel", TailnetHostname.fromModel("PJD110"))
        assertEquals("pixel-8-pro-petrel", TailnetHostname.fromModel("Pixel 8 Pro"))
        assertEquals("sm-s9280-petrel", TailnetHostname.fromModel("SM-S9280"))
        assertEquals("a-b-petrel", TailnetHostname.fromModel("  --A__B--  "))
    }

    @Test fun emptyOrUnusableModelFallsBack() {
        assertEquals("android-petrel", TailnetHostname.fromModel(null))
        assertEquals("android-petrel", TailnetHostname.fromModel(""))
        assertEquals("android-petrel", TailnetHostname.fromModel("小米"))
    }

    @Test fun longModelIsTruncatedWithoutTrailingDash() {
        val name = TailnetHostname.fromModel("x".repeat(39) + " yyyy")
        assertEquals("x".repeat(39) + "-petrel", name)
    }
}

class RestartAfterImportTest {
    private fun state(vpn: String, mode: ConnMode?) = CoreState(vpn, "Running", emptyList(), "", "", mode = mode)

    @Test fun stoppedNeverRestarts() {
        ConnMode.entries.forEach { assertFalse(restartAfterImport(CoreState.STOPPED, it, configPresent = true)) }
    }

    @Test fun tailnetOnlyKeepsRunning() {
        assertFalse(restartAfterImport(state("running", ConnMode.Tailnet), ConnMode.Tailnet, configPresent = true))
        assertFalse(restartAfterImport(state("starting", ConnMode.Tailnet), ConnMode.Tailnet, configPresent = true))
    }

    @Test fun pendingProxyPreferenceRestartsOnceThereIsAConfig() {
        // 没有配置时拨开了「代理」：内核还是仅 tailnet，偏好已经变了，导入配置要靠这次重启切过去
        assertTrue(restartAfterImport(state("running", ConnMode.Tailnet), ConnMode.Both, configPresent = true))
        assertTrue(restartAfterImport(state("running", ConnMode.Tailnet), ConnMode.Proxy, configPresent = true))
        // 同一个状态下只换了 GeoIP 库、还是没有配置：重启会因为没有配置而失败，把正在用的 tailnet 断掉
        assertFalse(restartAfterImport(state("running", ConnMode.Tailnet), ConnMode.Both, configPresent = false))
        assertFalse(restartAfterImport(state("running", ConnMode.Tailnet), ConnMode.Proxy, configPresent = false))
    }

    @Test fun modesThatReadTheConfigRestart() {
        assertTrue(restartAfterImport(state("running", ConnMode.Both), ConnMode.Both, configPresent = true))
        assertTrue(restartAfterImport(state("running", ConnMode.Proxy), ConnMode.Proxy, configPresent = true))
        assertTrue(restartAfterImport(state("running", ConnMode.Both), ConnMode.Tailnet, configPresent = true))
        // 刚点了连接、内核还没报模式：按要重启算
        assertTrue(restartAfterImport(state("starting", null), ConnMode.Tailnet, configPresent = true))
    }
}

class RestartAfterProxyToggleTest {
    private fun running(mode: ConnMode) = CoreState("running", "Running", emptyList(), "", "", mode = mode)

    @Test fun withAConfigEveryToggleRestarts() {
        assertTrue(restartAfterProxyToggle(running(ConnMode.Tailnet), ConnMode.Both, configPresent = true))
        assertTrue(restartAfterProxyToggle(running(ConnMode.Both), ConnMode.Tailnet, configPresent = true))
        assertFalse(restartAfterProxyToggle(CoreState.STOPPED, ConnMode.Both, configPresent = true))
    }

    @Test fun withoutAConfigTurningProxyOnOnlySavesThePreference() {
        // 仅 tailnet 在跑、还没导入配置：重启会因为没有配置而失败，把 tailnet 也断掉
        assertFalse(restartAfterProxyToggle(running(ConnMode.Tailnet), ConnMode.Both, configPresent = false))
        // 又拨回去：内核本来就在跑仅 tailnet，不用动
        assertFalse(restartAfterProxyToggle(running(ConnMode.Tailnet), ConnMode.Tailnet, configPresent = false))
        // 配置在别处被删了、内核还在跑 tailnet + 代理：关掉代理照常重启成仅 tailnet
        assertTrue(restartAfterProxyToggle(running(ConnMode.Both), ConnMode.Tailnet, configPresent = false))
    }
}

class GroupsParseTest {
    @Test fun chainThroughAFrontGroupReachesTs() {
        // Go 侧把 dialer-proxy 指向的组展开成「选中的成员 → 组」：ts 在链里，仅代理模式下认得出这个节点用不了
        val json = """[{"name":"PROXY","now":"EXIT","testURL":"u","proxies":[""" +
            """{"name":"EXIT","type":"Shadowsocks","chain":["ts","FRONT","EXIT"],"relays":[],"delay":120,"addr":"203.0.113.7:443"},""" +
            """{"name":"PLAIN","type":"Shadowsocks","chain":["FRONT2","PLAIN"],"relays":[],"delay":-1,"addr":"203.0.113.8:443"}]}]"""
        val (exit, plain) = GroupsRepository.parse(json).single().proxies
        assertEquals(listOf("ts", "FRONT", "EXIT"), exit.chain)
        assertTrue(exit.viaTailnet)
        assertFalse(plain.viaTailnet)
        assertEquals("EXIT|203.0.113.7:443", exit.ipKey)
    }
}
