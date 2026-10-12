package com.robb3n.petrel

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * 日志文件的纯逻辑：落盘规则与落盘前的改写、行格式、轮转、进程死因的名字。不碰 Android 类，JVM 单测覆盖（LogFileTest）。
 * 写线程、队列与 logcat 在 PLog.kt。
 */

/** 日志目录 `files/logs/` 下的当前文件与备份。 */
const val LOG_FILE = "petrel.log"
const val LOG_BACKUP = "petrel.log.1"
const val LOG_MAX_BYTES = 1_048_576L

private const val LOGIN_URL_LINE = "tailnet login URL:"
private const val MIHOMO_PREFIX = "mihomo: "

/**
 * 这一行要不要写进文件（logcat 照打不误）：
 * - `tailnet login URL:` 行带完整登录链接，硬边界只许它出现在设备 logcat，不落盘；
 * - mihomo 的 debug / info 行逐条记连接目标，等于浏览记录，不落盘；warning / error 照写。
 */
internal fun shouldPersist(level: Char, msg: String): Boolean = when {
    msg.startsWith(LOGIN_URL_LINE) -> false
    msg.startsWith(MIHOMO_PREFIX) && (level == 'D' || level == 'I') -> false
    else -> true
}

private val DIAL_FAILURE = Regex("""^mihomo: \[(TCP|UDP)] dial """)
private val MATCH_TYPE = Regex(""" \(match ([A-Za-z0-9]+)/""")

/** mihomo 的规则类型名（`constant/rule.go` 的 `RuleType.String()`）。括号那一段只认这些：节点名里也可能写着 `(match xxx/`。 */
private val RULE_TYPES = setOf(
    "Domain", "DomainSuffix", "DomainKeyword", "DomainRegex", "DomainWildcard", "GeoSite", "GeoIP", "SrcGeoIP",
    "IPASN", "SrcIPASN", "IPCIDR", "SrcIPCIDR", "IPSuffix", "SrcIPSuffix", "SrcPort", "DstPort", "InPort",
    "InUser", "InName", "InType", "ProcessName", "ProcessPath", "ProcessNameRegex", "ProcessPathRegex",
    "ProcessNameWildcard", "ProcessPathWildcard", "RematchName", "Match", "RuleSet", "Network", "DSCP", "Uid",
    "SubRules", "AND", "OR", "NOT",
)
private val IPV4_PORT = Regex("""\d{1,3}(\.\d{1,3}){3}:\d+""")

/**
 * 写进文件之前对一行的改写（logcat 照打全文）。目前只改 mihomo 的拨号失败行（warn 级，所以会落盘）：
 * `mihomo: [TCP] dial <节点> (match <规则类型>/<载荷>) <源> --> <目标:端口> error: <原因>`，没命中规则时没有括号那一段。
 * 目标地址等于浏览记录，节点名与规则载荷是配置内容，错误原因里也常带地址、偶尔带节点名。这些都是自由文本，
 * 没法逐个抠掉，所以整行按能确认安全的几样重写：协议、规则类型、目标的种类（IPv4 / IPv6 / 域名）、错误的种类（[dialErrorKind]）。
 * 写出去的每一段都是这里的字面量或表里的名字（[RULE_TYPES]、[ERROR_KINDS]），原文的片段不会带出来。其余行原样返回。
 */
internal fun redactForFile(msg: String): String {
    if (!msg.startsWith(MIHOMO_PREFIX)) return msg // 每条日志都经过这里：不是 mihomo 的行不进正则
    val head = DIAL_FAILURE.find(msg) ?: return msg
    val net = head.groupValues[1]
    val arrow = msg.lastIndexOf(" --> ")
    val error = if (arrow < 0) -1 else msg.indexOf(" error: ", arrow)
    if (error < 0) return "mihomo: [$net] dial <redacted>"
    val rule = MATCH_TYPE.findAll(msg.substring(head.range.last, arrow)).map { it.groupValues[1] }.firstOrNull { it in RULE_TYPES }
        ?.let { " (match $it)" }.orEmpty()
    val target = msg.substring(arrow + " --> ".length, error)
    val kind = when {
        target.startsWith("[") -> "<ipv6>"
        IPV4_PORT.matches(target) -> "<ipv4>"
        else -> "<host>"
    }
    return "mihomo: [$net] dial <node>$rule --> $kind error: ${dialErrorKind(msg.substring(error + " error: ".length))}"
}

/** 错误原因里认得出、且本身不带地址、域名、节点名的说法。互相包含的（`unexpected EOF` 与 `EOF`）只留长的。 */
private val ERROR_KINDS = listOf(
    "dns resolve failed", "ip version error", "couldn't find ip", "all DNS requests failed", "no such host",
    "connect error", "connection refused", "connection reset by peer", "connection timed out", "broken pipe",
    "network is unreachable", "no route to host", "host is unreachable",
    "i/o timeout", "context deadline exceeded", "context canceled",
    "handshake failure", "handshake timeout", "certificate", "unexpected EOF", "EOF",
    "UDP is not supported", "not found", "permission denied", "operation not permitted",
    "too many open files", "use of closed network connection",
)

/**
 * 拨号错误的种类：把 [reason] 里认得出的说法（[ERROR_KINDS]）按出现的先后用 `: ` 连起来，
 * 例如 `<服务器:端口> connect error: dial tcp <ip:端口>: i/o timeout` 得到 `connect error: i/o timeout`；一个都认不出时是 `other`。
 * 只输出表里的字面量，原文的任何片段都不会带出来。
 */
internal fun dialErrorKind(reason: String): String {
    val hits = ERROR_KINDS.mapNotNull { k -> reason.indexOf(k).takeIf { it >= 0 }?.let { it to k } }.sortedBy { it.first }
    return hits
        .filter { (i, k) -> hits.none { (j, o) -> o != k && j <= i && i + k.length <= j + o.length } }
        .joinToString(": ") { it.second }
        .ifEmpty { "other" }
}

private val LINE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
private val SECOND_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** 一条日志一行：`yyyy-MM-dd HH:mm:ss.SSS <pid> <D|I|W|E> <msg>`，本地时区；msg 里的换行转成 `\n` 字面量。不带行尾换行。 */
internal fun formatLine(timeMs: Long, pid: Int, level: Char, msg: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = LINE_TIME.format(Instant.ofEpochMilli(timeMs).atZone(zone))
    val oneLine = msg.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\r")
    return "$time $pid $level $oneLine"
}

/** 秒级时间（进程死因、导出头部用）。 */
internal fun formatSecond(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    SECOND_TIME.format(Instant.ofEpochMilli(timeMs).atZone(zone))

/** 异常的类名链 `IOException ← ErrnoException`：不带 message（可能含配置内容或路径），也不带栈。 */
internal fun throwableChain(tr: Throwable): String {
    val names = mutableListOf<String>()
    var t: Throwable? = tr
    val seen = HashSet<Throwable>()
    while (t != null && seen.add(t) && names.size < 10) {
        names += t.javaClass.simpleName.ifEmpty { t.javaClass.name }
        t = t.cause
    }
    return names.joinToString(" ← ")
}

/**
 * 崩溃时写进文件的几行：`FATAL in <线程名>: <类名>`、每层最多 30 帧 `  at <frame>`，cause 每层 `  caused by <类名>` 加它的帧。
 * 不带 message。返回的是 msg（不含时间、pid、级别），由调用方逐行 [formatLine]。
 */
internal fun fatalLines(threadName: String, e: Throwable, maxFrames: Int = 30): List<String> {
    val out = mutableListOf<String>()
    var t: Throwable? = e
    val seen = HashSet<Throwable>()
    while (t != null && seen.add(t)) {
        val name = t.javaClass.name
        out += if (t === e) "FATAL in $threadName: $name" else "  caused by $name"
        t.stackTrace.take(maxFrames).forEach { out += "  at $it" }
        t = t.cause
    }
    return out
}

/** ApplicationExitInfo 的 REASON_* 名字（API 30–36）；未知值输出 `REASON_<int>`。用字面量，不依赖编译用的 SDK 里有没有这个常量。 */
internal fun exitReasonName(reason: Int): String = when (reason) {
    0 -> "REASON_UNKNOWN"
    1 -> "REASON_EXIT_SELF"
    2 -> "REASON_SIGNALED"
    3 -> "REASON_LOW_MEMORY"
    4 -> "REASON_CRASH"
    5 -> "REASON_CRASH_NATIVE"
    6 -> "REASON_ANR"
    7 -> "REASON_INITIALIZATION_FAILURE"
    8 -> "REASON_PERMISSION_CHANGE"
    9 -> "REASON_EXCESSIVE_RESOURCE_USAGE"
    10 -> "REASON_USER_REQUESTED"
    11 -> "REASON_USER_STOPPED"
    12 -> "REASON_DEPENDENCY_DIED"
    13 -> "REASON_OTHER"
    14 -> "REASON_FREEZER"
    15 -> "REASON_PACKAGE_STATE_CHANGE"
    16 -> "REASON_PACKAGE_UPDATED"
    else -> "REASON_$reason"
}

/** 崩溃类死因（CRASH / CRASH_NATIVE / ANR）记成 W，其余 I。 */
internal fun exitReasonIsCrash(reason: Int): Boolean = reason == 4 || reason == 5 || reason == 6

/** 进程死因的一行：`exit <REASON> at <yyyy-MM-dd HH:mm:ss> importance=<int> pss=<KB> rss=<KB> desc=<description 或 ->`。 */
internal fun formatExitLine(
    reason: Int,
    timestampMs: Long,
    importance: Int,
    pssKb: Long,
    rssKb: Long,
    description: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val desc = description?.takeIf { it.isNotBlank() }?.replace("\n", "\\n") ?: "-"
    return "exit ${exitReasonName(reason)} at ${formatSecond(timestampMs, zone)} importance=$importance pss=$pssKb rss=$rssKb desc=$desc"
}

/**
 * [dir] 下的按大小轮转的日志文件：写 [LOG_FILE]，再写这一行会超过 [maxBytes] 时把它改名成 [LOG_BACKUP]（覆盖旧的）、
 * 重开一个新的。目录里最多两个文件。常开一个 append 流，每行 flush。不是线程安全的：调用方持锁。IO 失败直接抛给调用方。
 */
internal class RotatingLogFile(private val dir: File, private val maxBytes: Long = LOG_MAX_BYTES) {
    private var out: FileOutputStream? = null
    private var size = 0L

    /** 写一行（[line] 不带行尾换行）。 */
    fun write(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        var o = out ?: open()
        if (size > 0 && size + bytes.size > maxBytes) {
            rotate()
            o = open()
        }
        o.write(bytes)
        o.flush()
        size += bytes.size
    }

    fun close() {
        runCatching { out?.close() }
        out = null
    }

    private fun open(): FileOutputStream {
        dir.mkdirs()
        val f = File(dir, LOG_FILE)
        return FileOutputStream(f, true).also {
            out = it
            size = f.length()
        }
    }

    private fun rotate() {
        close()
        val current = File(dir, LOG_FILE)
        val backup = File(dir, LOG_BACKUP)
        backup.delete()
        // 改名失败（备份那个位置删不掉、被占着）时备份已经没了，当前文件却还是超限的：留着它的话，之后每写一行都重走一遍轮转，
        // 上限就失效了。这一段保不住，丢掉它从空文件重新开始；删不掉就截断，截断也不行则抛给调用方
        if (!current.renameTo(backup) && !current.delete()) FileOutputStream(current).close()
        size = 0
    }
}

/**
 * 把 [dir] 里的备份与当前日志依次拷进 [out]（先旧后新），返回拷了多少字节。
 * 调用方要持着写线程的锁（[PLog.withFiles]）：两个文件分开打开的话，中间撞上轮转，刚轮转出去的那一整段就漏了。
 */
internal fun copyLogFiles(dir: File, out: OutputStream): Long =
    listOf(File(dir, LOG_BACKUP), File(dir, LOG_FILE)).filter { it.isFile }.sumOf { f -> f.inputStream().use { it.copyTo(out) } }
