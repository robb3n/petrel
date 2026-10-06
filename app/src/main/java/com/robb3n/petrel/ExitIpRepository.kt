package com.robb3n.petrel

import android.content.Context
import android.util.Log
import com.robb3n.petrel.core.ptcore.Ptcore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 一次出口 IP 查询的结果（ip-api 的字段，地名是中文）。 */
data class ExitIpInfo(
    val ip: String,
    val countryCode: String,
    val country: String,
    val region: String,
    val city: String,
    val isp: String,
    /** 「AS2914」；没有时为空串。 */
    val asn: String,
    /** 机房 IP。 */
    val hosting: Boolean,
    /** 移动网络。 */
    val mobile: Boolean,
)

/** 解析 ip-api 的应答；`status` 不是 success 或缺 IP 时返回 null。 */
fun parseExitIp(json: String): ExitIpInfo? = runCatching {
    val o = JSONObject(json)
    if (o.optString("status") != "success") return null
    val ip = o.optString("query")
    if (ip.isEmpty()) return null
    ExitIpInfo(
        ip = ip,
        countryCode = o.optString("countryCode"),
        country = o.optString("country"),
        region = o.optString("regionName"),
        city = o.optString("city"),
        isp = o.optString("isp"),
        asn = o.optString("as").substringBefore(' ').takeIf { it.startsWith("AS") }.orEmpty(),
        hosting = o.optBoolean("hosting"),
        mobile = o.optBoolean("mobile"),
    )
}.getOrNull()

private fun ExitIpInfo.toJson(): JSONObject = JSONObject()
    .put("query", ip).put("countryCode", countryCode).put("country", country).put("regionName", region)
    .put("city", city).put("isp", isp).put("as", asn).put("hosting", hosting).put("mobile", mobile).put("status", "success")

/** 当前出口的出口 IP：[info] 是查到的（查询中时先显示记下的那份，可能为 null），[failed] 是这次没查到。 */
data class CurrentExitIp(val info: ExitIpInfo?, val loading: Boolean, val failed: Boolean)

/**
 * 出口 IP：当前出口的实时查询，加上各节点记下的出口 IP（节点页显示）。
 * - 当前出口在连上、切换、刷新时重查（仅 tailnet 模式另在切网后重查：那时的出口就是手机自己的网络）。
 * - 每个节点第一次被用作出口时记下；「测延迟」测完后给还没记下的节点补查（直接经该节点查，不切换当前出口）。
 * - 记录按「节点名 + 服务器地址」对应，存在 `files/exitip.json`；配置里没了的节点随之删掉。
 * IP 属于连接信息，不写进日志。
 */
object ExitIpRepository {
    private const val FILE = "exitip.json"
    private const val FILL_PARALLEL = 3
    private const val NET_DEBOUNCE_MILLIS = 3_000L
    private const val DIRECT_TIMEOUT_MILLIS = 8_000

    /**
     * 当前出口查询失败后的重试间隔。VPN 刚起来、tailnet 刚到 Running 时经 `ts` 的通路还是冷的
     * （内核要预热最多 10 秒，见 docs/lessons/network-change.md「首轮测速」），第一次查询常常立即失败；
     * 这几次重试把预热窗口盖过去，都失败才显示「查不到」。
     */
    private val RETRY_DELAYS_MILLIS = longArrayOf(3_000, 5_000, 8_000)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _cache = MutableStateFlow<Map<String, ExitIpInfo>>(emptyMap())
    /** 记下的出口 IP，键见 [ProxyUi.ipKey]。 */
    val cache: StateFlow<Map<String, ExitIpInfo>> = _cache

    private val _current = MutableStateFlow<CurrentExitIp?>(null)
    /** null = 此刻没有可查的出口（VPN 没跑、tailnet 没就绪、没有代理组、出口要经过没开的 tailnet）。 */
    val current: StateFlow<CurrentExitIp?> = _current

    private val _filling = MutableStateFlow(false)
    /** 正在给没记下的节点补查。 */
    val filling: StateFlow<Boolean> = _filling

    /** 刷新、切网时加 1，让同一个出口也重查。 */
    private val requeryGen = MutableStateFlow(0)

    @Volatile private var file: File? = null
    @Volatile private var started = false
    /** 每个查询目标一个号，只在 [onTarget] 与查询回来时（都持有本对象的锁）读写。 */
    private var token = 0
    private var netJob: Job? = null

    private fun key(p: ProxyUi): String? = p.ipKey

    /** 查询的对象：[node] 为 null 表示不经代理、直接查（仅 tailnet 模式）。 */
    private data class Target(val node: String?, val key: String?, val gen: Int)

    /** 幂等，由 Application 调用一次。 */
    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        file = File(ctx.filesDir, FILE)
        scope.launch {
            load()
            combine(CoreBridge.state, GroupsRepository.groups, requeryGen) { s, groups, gen ->
                prune(groups)
                target(s, groups, gen)
            }.distinctUntilChanged().collect { t -> onTarget(t) }
        }
    }

    private fun target(s: CoreState, groups: List<ProxyGroupUi>, gen: Int): Target? {
        if (!s.exitReady) return null
        if (s.mode == ConnMode.Tailnet) return Target(null, null, gen)
        val g = groups.firstOrNull() ?: return null
        val p = g.proxies.firstOrNull { it.name == g.now } ?: return null
        if (s.mode == ConnMode.Proxy && p.viaTailnet) return null
        return Target(p.name, key(p), gen)
    }

    @Synchronized
    private fun onTarget(t: Target?) {
        val my = ++token
        if (t == null) {
            _current.value = null
            return
        }
        _current.value = CurrentExitIp(t.key?.let { _cache.value[it] }, loading = true, failed = false)
        scope.launch {
            // Go 调用不可取消：每次查完、每次等完都看出口在这期间变没变
            var info = query(t.node)
            for (wait in RETRY_DELAYS_MILLIS) {
                if (info != null || !stillCurrent(my)) break
                delay(wait)
                if (!stillCurrent(my)) break
                info = query(t.node)
            }
            synchronized(this@ExitIpRepository) {
                // 期间出口变了（切换、停止、刷新）：这次的结果作废，新目标自己会查
                if (my != token) return@launch
                _current.value = CurrentExitIp(info, loading = false, failed = info == null)
            }
            if (info != null && t.key != null) remember(t.key, info)
        }
    }

    @Synchronized
    private fun stillCurrent(my: Int) = my == token

    /** 刷新：当前出口重查一次。 */
    fun requery() = requeryGen.update { it + 1 }

    /** 底层网络变了：仅 tailnet 模式下出口就是手机自己的网络，去抖后重查。 */
    @Synchronized
    fun networkChanged() {
        netJob?.cancel()
        netJob = scope.launch {
            delay(NET_DEBOUNCE_MILLIS)
            if (CoreBridge.state.value.mode == ConnMode.Tailnet) requery()
        }
    }

    /** 给还没记下出口 IP 的节点补查（测延迟之后）。仅代理模式下经 tailnet 的节点跳过。 */
    fun fillMissing() {
        val s = CoreBridge.state.value
        if (!s.exitReady || s.mode == ConnMode.Tailnet) return
        if (!_filling.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                val todo = GroupsRepository.groups.value.flatMap { it.proxies }
                    .filter { p -> key(p).let { it != null && it !in _cache.value } && !(s.mode == ConnMode.Proxy && p.viaTailnet) }
                    .distinctBy { it.name }
                val gate = Semaphore(FILL_PARALLEL)
                todo.map { p ->
                    async {
                        gate.withPermit { query(p.name)?.let { info -> key(p)?.let { remember(it, info) } } }
                    }
                }.awaitAll()
            } finally {
                _filling.value = false
            }
        }
    }

    /** 经节点 [node] 查（Go 侧直接经该节点拨号）；[node] 为 null 时本进程直接查（本 App 被排除在 VPN 外，走的就是手机网络）。 */
    private fun query(node: String?): ExitIpInfo? = runCatching {
        val body = if (node == null) directGet() else Ptcore.exitIP(node)
        parseExitIp(body)
    }.onFailure { Log.w(TAG, "exit ip query failed: ${it.javaClass.simpleName}") }.getOrNull()

    private fun directGet(): String {
        val c = URL(Ptcore.ExitIPURL).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = DIRECT_TIMEOUT_MILLIS
            c.readTimeout = DIRECT_TIMEOUT_MILLIS
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun remember(key: String, info: ExitIpInfo) {
        _cache.update { it + (key to info) }
        save()
    }

    /** 配置里没了的节点（名字或地址变了）不再留着。组为空（VPN 没跑、仅 tailnet）时不动。 */
    private fun prune(groups: List<ProxyGroupUi>) {
        if (groups.isEmpty()) return
        val live = groups.flatMap { it.proxies }.mapNotNull(::key).toSet()
        val before = _cache.value
        val after = before.filterKeys { it in live }
        if (after.size != before.size) {
            _cache.value = after
            save()
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            _cache.value = o.keys().asSequence().mapNotNull { k -> parseExitIp(o.getJSONObject(k).toString())?.let { k to it } }.toMap()
        }.onFailure { Log.w(TAG, "exit ip cache unreadable: ${it.javaClass.simpleName}") }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        val o = JSONObject()
        _cache.value.forEach { (k, v) -> o.put(k, v.toJson()) }
        runCatching {
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) error("rename failed")
        }.onFailure { Log.w(TAG, "exit ip cache not saved: ${it.javaClass.simpleName}") }
    }
}
