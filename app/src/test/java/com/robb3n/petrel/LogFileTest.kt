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
