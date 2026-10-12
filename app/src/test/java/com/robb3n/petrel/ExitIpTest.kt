package com.robb3n.petrel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseExitIpTest {
    @Test fun successResponse() {
        val json = """{"status":"success","country":"美国","countryCode":"US","regionName":"加利福尼亚州","city":"洛杉矶",""" +
            """"isp":"NTT America, Inc.","as":"AS2914 NTT America, Inc.","mobile":false,"hosting":true,"query":"203.0.113.42"}"""
        assertEquals(
            ExitIpInfo("203.0.113.42", "US", "美国", "加利福尼亚州", "洛杉矶", "NTT America, Inc.", "AS2914", hosting = true, mobile = false),
            parseExitIp(json),
        )
    }

    @Test fun failuresAreNull() {
        assertNull(parseExitIp("""{"status":"fail","message":"reserved range","query":"10.0.0.1"}"""))
        assertNull(parseExitIp("""{"status":"success"}"""))
        assertNull(parseExitIp("not json"))
    }

    @Test fun missingAsIsEmpty() {
        val info = parseExitIp("""{"status":"success","query":"192.0.2.1","as":""}""")!!
        assertEquals("", info.asn)
    }
}

class ExitLeafTest {
    private fun node(name: String, vararg chain: String) =
        ProxyUi(name, "ss", if (chain.isEmpty()) listOf(name) else chain.toList(), -1, addr = "$name.example:443")

    private fun group(name: String, now: String, vararg members: ProxyUi) = ProxyGroupUi(name, now, "", members.toList())

    private fun sub(name: String) = ProxyUi(name, "Selector", listOf(name), -1)

    @Test fun directMemberIsTheLeaf() {
        val a = node("A")
        assertEquals(a, exitLeaf(listOf(group("PROXY", "A", a, node("B")))))
    }

    @Test fun followsNestedSelections() {
        val a = node("A")
        val b = node("B", "ts", "B")
        val groups = listOf(group("PROXY", "REGION", sub("REGION")), group("REGION", "A", a, b))
        assertEquals(a, exitLeaf(groups))
        // 在子组里换节点：主组这边一个字没变，叶子变了
        val switched = listOf(groups[0], groups[1].copy(now = "B"))
        assertEquals(b, exitLeaf(switched))
        assertEquals("B|B.example:443", exitLeaf(switched)!!.ipKey)
        assertTrue(exitLeaf(switched)!!.viaTailnet)
    }

    @Test fun threeLevels() {
        val a = node("A")
        val groups = listOf(
            group("PROXY", "REGION", sub("REGION")),
            group("REGION", "CITY", sub("CITY")),
            group("CITY", "A", a),
        )
        assertEquals(a, exitLeaf(groups))
    }

    @Test fun groupsNotListedAreLeaves() {
        // url-test 这类自动组不在可切换组里：停在它身上，经这个组去查
        val auto = ProxyUi("AUTO", "URLTest", listOf("AUTO"), -1)
        assertEquals(auto, exitLeaf(listOf(group("PROXY", "AUTO", auto))))
    }

    @Test fun cyclesStop() {
        val groups = listOf(group("PROXY", "LOOP", sub("LOOP")), group("LOOP", "PROXY", sub("PROXY")))
        assertEquals(sub("PROXY"), exitLeaf(groups))
        val self = listOf(group("PROXY", "PROXY", sub("PROXY")))
        assertEquals(sub("PROXY"), exitLeaf(self))
    }

    @Test fun missingSelections() {
        assertNull(exitLeaf(emptyList()))
        assertNull(exitLeaf(listOf(group("PROXY", "gone", node("A")))))
        // 子组里找不到选中项：停在子组上
        val groups = listOf(group("PROXY", "REGION", sub("REGION")), group("REGION", "gone", node("A")))
        assertEquals(sub("REGION"), exitLeaf(groups))
    }
}

class ReadCappedTest {
    @Test fun shortInputIsReadWhole() {
        assertEquals("hello", readCapped("hello".byteInputStream(), 4096).toString(Charsets.UTF_8))
        assertEquals(0, readCapped(ByteArray(0).inputStream(), 4096).size)
    }

    @Test fun endlessInputStopsAtTheCap() {
        var served = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int {
                served++
                return 'x'.code
            }

            // 一次只给一点：上限要跨多次读累计
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = minOf(len, 100)
                java.util.Arrays.fill(b, off, off + n, 'x'.code.toByte())
                served += n
                return n
            }
        }
        val got = readCapped(endless, 4096)
        assertEquals(4096, got.size)
        assertEquals(4096L, served)
    }
}
