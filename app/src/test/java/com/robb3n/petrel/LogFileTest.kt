package com.robb3n.petrel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class LogFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val utc8: ZoneId = ZoneOffset.ofHours(8)

    @Test
    fun loginUrlLineNotPersisted() {
        assertFalse(shouldPersist('I', "tailnet login URL: https://login.example/a/abc"))
    }

    @Test
    fun mihomoDebugAndInfoNotPersisted() {
        assertFalse(shouldPersist('D', "mihomo: [TCP] dial PROXY"))
        assertFalse(shouldPersist('I', "mihomo: [TCP] 127.0.0.1:1 --> example.com:443"))
    }

    @Test
    fun mihomoWarningAndErrorPersisted() {
        assertTrue(shouldPersist('W', "mihomo: dial failed"))
        assertTrue(shouldPersist('E', "mihomo: start failed"))
    }

    @Test
    fun tsnetDebugPersisted() {
        assertTrue(shouldPersist('D', "tsnet: magicsock: endpoints changed"))
    }

    @Test
    fun plainLinePersisted() {
        assertTrue(shouldPersist('I', "vpn started"))
    }

    @Test
    fun formatLineHasTimePidLevel() {
        val t = LocalDateTime.of(2026, 10, 7, 9, 5, 3, 42_000_000).atZone(utc8).toInstant().toEpochMilli()
        assertEquals("2026-10-07 09:05:03.042 1234 W vpn started", formatLine(t, 1234, 'W', "vpn started", utc8))
    }

    @Test
    fun formatLineEscapesNewlines() {
        val line = formatLine(0, 1, 'I', "a\nb\r\nc", ZoneOffset.UTC)
        assertEquals("1970-01-01 00:00:00.000 1 I a\\nb\\nc", line)
        assertFalse(line.contains('\n'))
    }

    @Test
    fun rotatesAtLimitKeepingTwoFiles() {
        val dir = tmp.newFolder("logs")
        val log = RotatingLogFile(dir, maxBytes = 1_048_576)
        val line = "x".repeat(1023) // 加换行 1024 字节
        repeat(1024) { log.write(line) } // 正好 1 MiB
        assertFalse(File(dir, LOG_BACKUP).exists())
        log.write("first after rotation")
        assertTrue(File(dir, LOG_BACKUP).exists())
        assertEquals(1_048_576L, File(dir, LOG_BACKUP).length())
        assertEquals("first after rotation\n", File(dir, LOG_FILE).readText())

        repeat(1024) { log.write(line) } // 前面已有 21 字节，最后一行触发第二次轮转
        log.write("second rotation")
        log.close()
        assertTrue(File(dir, LOG_BACKUP).readText().startsWith("first after rotation\n"))
        assertTrue(File(dir, LOG_BACKUP).length() <= 1_048_576L)
        assertEquals("$line\nsecond rotation\n", File(dir, LOG_FILE).readText())
        assertEquals(setOf(LOG_FILE, LOG_BACKUP), dir.list()!!.toSet())
    }

    @Test
    fun failedRotationStillHonoursTheLimit() {
        val dir = tmp.newFolder("logs")
        // 备份那个位置是个非空目录：删不掉，改名也盖不上去
        File(dir, LOG_BACKUP).apply { mkdirs() }.resolve("keep").writeText("x")
        val log = RotatingLogFile(dir, maxBytes = 100)
        val line = "x".repeat(39) // 加换行 40 字节
        repeat(10) { i ->
            log.write(line)
            assertTrue("after line $i: ${File(dir, LOG_FILE).length()}", File(dir, LOG_FILE).length() <= 100)
        }
        log.write("tail")
        log.close()
        // 每满一次丢掉旧的一段、从空文件重新开始；最近写的行都在，内部记的大小与实际一致（否则上面早就超限了）
        assertEquals("$line\n$line\ntail\n", File(dir, LOG_FILE).readText())
        assertTrue(File(dir, LOG_BACKUP).isDirectory)
    }

    @Test
    fun copiesBackupThenCurrent() {
        val dir = tmp.newFolder("logs")
        val out = java.io.ByteArrayOutputStream()
        assertEquals(0L, copyLogFiles(dir, out))
        File(dir, LOG_FILE).writeText("new\n")
        assertEquals(4L, copyLogFiles(dir, out))
        File(dir, LOG_BACKUP).writeText("old\n")
        out.reset()
        assertEquals(8L, copyLogFiles(dir, out))
        assertEquals("old\nnew\n", out.toString("UTF-8"))
    }

    @Test
    fun dialFailureKeepsOnlyRuleTypeAndErrorKind() {
        assertEquals(
            "mihomo: [TCP] dial <node> (match GeoIP) --> <ipv6> error: dns resolve failed: ip version error",
            redactForFile("mihomo: [TCP] dial DIRECT (match GeoIP/cn) 172.19.0.1:41822 --> [240e:e1:aa00:1::7]:443 error: dns resolve failed: ip version error"),
        )
        assertEquals(
            "mihomo: [TCP] dial <node> (match RuleSet) --> <host> error: connect error: i/o timeout",
            redactForFile(
                "mihomo: [TCP] dial 东京 IIJ (01) (match RuleSet/petrel-cn-domains) 172.19.0.1:50112(com.example.app) --> www.example.com:443 " +
                    "error: 203.0.113.7:1080 connect error: dial tcp 203.0.113.7:1080: i/o timeout",
            ),
        )
        assertEquals(
            "mihomo: [UDP] dial <node> (match DomainSuffix) --> <ipv4> error: UDP is not supported",
            redactForFile("mihomo: [UDP] dial us-lax (match DomainSuffix/example.org) 172.19.0.1:5353 --> 8.8.8.8:53 error: proxy adapter [us-lax] UDP is not supported"),
        )
        // 没命中规则的两种写法（TCP、UDP）没有括号那一段
        assertEquals(
            "mihomo: [TCP] dial <node> --> <ipv4> error: connection refused",
            redactForFile("mihomo: [TCP] dial PROXY mihomo --> 1.1.1.1:443 error: dial tcp 1.1.1.1:443: connect: connection refused"),
        )
        assertEquals(
            "mihomo: [UDP] dial <node> --> <ipv6> error: context deadline exceeded",
            redactForFile("mihomo: [UDP] dial PROXY [fdfe:dcba:9876::1]:40000 --> [2001:4860:4860::8888]:443 error: context deadline exceeded"),
        )
        // 载荷为空的 MATCH、带逗号和括号的逻辑规则
        assertEquals(
            "mihomo: [TCP] dial <node> (match Match) --> <host> error: unexpected EOF",
            redactForFile("mihomo: [TCP] dial us (match Match/) 172.19.0.1:1 --> a.example:443 error: unexpected EOF"),
        )
        assertEquals(
            "mihomo: [TCP] dial <node> (match AND) --> <host> error: other",
            redactForFile("mihomo: [TCP] dial us (match AND/((DOMAIN,secret.example),(NETWORK,TCP))) 172.19.0.1:1 --> secret.example:443 error: remote said no to secret.example"),
        )
    }

    @Test
    fun dialFailureNeverLeaksItsFreeText() {
        val secrets = listOf("东京", "IIJ", "secret.example", "203.0.113.7", "240e", "172.19.0.1", "com.example.app", "cn-domains", "hunter2")
        val lines = listOf(
            "mihomo: [TCP] dial 东京 IIJ (match RuleSet/petrel-cn-domains) 172.19.0.1:50112(com.example.app) --> secret.example:443 error: 203.0.113.7:1080 connect error: hunter2",
            "mihomo: [TCP] dial 东京 --> IIJ (match DomainSuffix/secret.example) 172.19.0.1:1 --> [240e::1]:443 error: lookup secret.example on 203.0.113.7:53: no such host",
            "mihomo: [UDP] dial IIJ error: hunter2 172.19.0.1:1 --> 203.0.113.7:53 error: read udp 172.19.0.1:1->203.0.113.7:53: i/o timeout",
            "mihomo: [TCP] dial 东京 secret.example 203.0.113.7",
        )
        lines.forEach { line ->
            val out = redactForFile(line)
            assertTrue(out, out.startsWith("mihomo: [") && secrets.none { out.contains(it) })
        }
        // 节点名里写着 (match xxx/ 的：没命中规则时不能把名字里那一段当规则类型带出去；命中规则时认后面真的那一个
        assertEquals(
            "mihomo: [TCP] dial <node> --> <host> error: i/o timeout",
            redactForFile("mihomo: [TCP] dial HK (match Premium/1) 172.19.0.1:1 --> a.example:443 error: i/o timeout"),
        )
        assertEquals(
            "mihomo: [TCP] dial <node> (match GeoIP) --> <host> error: i/o timeout",
            redactForFile("mihomo: [TCP] dial HK (match Premium/1) (match GeoIP/cn) 172.19.0.1:1 --> a.example:443 error: i/o timeout"),
        )
        assertEquals("mihomo: [TCP] dial <redacted>", redactForFile(lines.last()))
    }

    @Test
    fun otherLinesAreNotRewritten() {
        listOf(
            "vpn started",
            "state {\"vpn\":\"running\"}",
            "mihomo: Start TUN listening error: something",
            "mihomo: [TCP] 127.0.0.1:1 --> example.com:443 match Match using PROXY",
            "tsnet: dial tcp failed",
        ).forEach { assertEquals(it, redactForFile(it)) }
    }

    @Test
    fun dialErrorKinds() {
        assertEquals("i/o timeout", dialErrorKind("dial tcp 1.2.3.4:443: i/o timeout"))
        assertEquals("connect error: connection refused", dialErrorKind("127.0.0.1:1 connect error: dial tcp 127.0.0.1:1: connect: connection refused"))
        assertEquals("unexpected EOF", dialErrorKind("unexpected EOF"))
        assertEquals("EOF", dialErrorKind("EOF"))
        assertEquals("connection reset by peer", dialErrorKind("read tcp 10.0.0.2:5555->1.2.3.4:443: read: connection reset by peer"))
        assertEquals("handshake failure", dialErrorKind("remote error: tls: handshake failure"))
        assertEquals("other", dialErrorKind("something new about node-a at 1.2.3.4"))
        assertEquals("other", dialErrorKind(""))
    }

    @Test
    fun reopensAppendingToExistingFile() {
        val dir = tmp.newFolder("logs")
        RotatingLogFile(dir).apply { write("a"); close() }
        RotatingLogFile(dir).apply { write("b"); close() }
        assertEquals("a\nb\n", File(dir, LOG_FILE).readText())
    }

    @Test
    fun exitReasonNames() {
        assertEquals("REASON_UNKNOWN", exitReasonName(0))
        assertEquals("REASON_CRASH_NATIVE", exitReasonName(5))
        assertEquals("REASON_USER_REQUESTED", exitReasonName(10))
        assertEquals("REASON_PACKAGE_UPDATED", exitReasonName(16))
        assertEquals("REASON_99", exitReasonName(99))
        assertTrue(exitReasonIsCrash(6))
        assertFalse(exitReasonIsCrash(10))
    }

    @Test
    fun exitLineFormat() {
        val t = LocalDateTime.of(2026, 10, 7, 9, 5, 3).atZone(utc8).toInstant().toEpochMilli()
        assertEquals(
            "exit REASON_USER_REQUESTED at 2026-10-07 09:05:03 importance=125 pss=1000 rss=2000 desc=-",
            formatExitLine(10, t, 125, 1000, 2000, null, utc8),
        )
        assertTrue(formatExitLine(13, t, 400, 0, 0, "remove task", utc8).endsWith("desc=remove task"))
    }

    @Test
    fun throwableChainHasClassNamesOnly() {
        val e = IOException("secret path /data/config.yaml", IllegalStateException("token=abc"))
        val chain = throwableChain(e)
        assertEquals("IOException ← IllegalStateException", chain)
        assertFalse(chain.contains("secret") || chain.contains("token"))
    }

    @Test
    fun fatalLinesHaveFramesAndCausesWithoutMessage() {
        val e = RuntimeException("secret", IOException("token"))
        val lines = fatalLines("main", e, maxFrames = 2)
        assertEquals("FATAL in main: java.lang.RuntimeException", lines.first())
        assertTrue(lines.contains("  caused by java.io.IOException"))
        assertTrue(lines.drop(1).takeWhile { !it.startsWith("  caused by") }.size <= 2)
        assertTrue(lines.none { it.contains("secret") || it.contains("token") })
    }
}
