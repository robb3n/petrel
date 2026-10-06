package com.robb3n.petrel

import android.util.Log
import com.robb3n.petrel.core.ptcore.Ptcore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray

data class ProxyUi(
    val name: String,
    val type: String,
    /** 第一跳 → … → 成员自己 */
    val chain: List<String>,
    /** -1 没测过，0 失败 / 超时，其余为毫秒 */
    val delay: Int,
    /** [chain] 里由配置的 `petrel-via` 标注出来的中转（不是 mihomo 节点） */
    val relays: List<String> = emptyList(),
    /** 服务器地址（host:port）；组、DIRECT 等没有地址的为空串。记下的出口 IP 按「名字 + 地址」对应。 */
    val addr: String = "",
) {
    /** 链路经过 tailnet（`ts`）：仅代理模式下不可用。 */
    val viaTailnet: Boolean get() = TS_NODE in chain

    /** 记下的出口 IP 的键：「名字|地址」；没有服务器地址的成员（组、DIRECT）不记，为 null。 */
    val ipKey: String? get() = if (addr.isEmpty()) null else "$name|$addr"
}

/** Petrel 注入的 tailnet 节点名（Go 侧 `tsProxyName`）。 */
const val TS_NODE = "ts"

data class ProxyGroupUi(
    val name: String,
    val now: String,
    val testURL: String,
    val proxies: List<ProxyUi>,
)

/** 可切换组的数据：跟随 CoreBridge.state，VPN 不在 running 时清空，进入 running 或 groupsRev 变化时重拉。 */
object GroupsRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _groups = MutableStateFlow<List<ProxyGroupUi>>(emptyList())
    val groups: StateFlow<List<ProxyGroupUi>> = _groups
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    @Volatile private var started = false

    /** 幂等，由 Application 调用一次。 */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // running 时把 groupsRev 带进 key；离开 running 统一折成 -1，清空一次
            CoreBridge.state
                .map { if (it.running) it.groupsRev else -1 }
                .distinctUntilChanged()
                .collect { rev ->
                    if (rev < 0) _groups.value = emptyList() else reload()
                }
        }
    }

    private fun reload() {
        val parsed = runCatching { parse(Ptcore.groups()) }
            .onFailure { Log.w(TAG, "groups failed", it) }
            .getOrNull() ?: return
        _groups.value = parsed
    }

    /** 乐观更新选中项，失败时回滚并回调错误信息（在 IO 线程回调）。 */
    fun select(group: String, name: String, onError: (String) -> Unit) {
        // 两处写都用 update（CAS）：reload 在另一个 IO 线程上整表替换，非原子的读改写会把它刚写进来的延迟盖回旧值
        val old = _groups.value.firstOrNull { it.name == group }?.now
        _groups.update { gs -> gs.map { if (it.name == group) it.copy(now = name) else it } }
        scope.launch {
            runCatching { Ptcore.selectProxy(group, name) }.onFailure {
                // 错误信息带着组名与节点名（配置内容），只给界面
                Log.w(TAG, "select failed: ${it.javaClass.simpleName}")
                // 只回滚这一组、且仍停在这次失败的选择上时才回滚：期间人又选了别的节点（可能已经成功），不能把它也撤掉
                if (old != null) {
                    _groups.update { gs -> gs.map { g -> if (g.name == group && g.now == name) g.copy(now = old) else g } }
                }
                onError(it.message ?: "未知错误")
            }
        }
    }

    /** 测所有组的延迟，进行中 [testing] 为 true；测完顺带给还没记下出口 IP 的节点补查（[ExitIpRepository.fillMissing]）。 */
    fun testDelay() {
        if (!_testing.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                runCatching { Ptcore.testGroupDelay("") }.onFailure { Log.w(TAG, "test delay failed", it) }
            } finally {
                _testing.value = false
            }
            ExitIpRepository.fillMissing()
        }
    }

    /** 首页的刷新：预热 tailnet 第一跳 + 测所有组（与 [testDelay] 共用 [testing]），再重拉 tailnet 节点列表。 */
    fun refresh() {
        if (!_testing.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                ExitIpRepository.requery()
                // 仅 tailnet 没有代理组，内核的 Refresh 没事可做；只重拉节点列表
                if (CoreBridge.state.value.mode != ConnMode.Tailnet) {
                    runCatching { Ptcore.refresh() }.onFailure { Log.w(TAG, "refresh failed", it) }
                }
                runCatching { TailnetRepository.refresh() }.onFailure { Log.w(TAG, "tailnet refresh failed", it) }
            } finally {
                _testing.value = false
            }
        }
    }

    @Volatile private var probing = false

    /** 探测各组当前选中的节点；Go 侧跳过 25 秒内测过的，上一轮还没结束时不叠加。不影响 [testing]。 */
    fun probeSelected() {
        if (probing) return
        probing = true
        scope.launch {
            try {
                runCatching { Ptcore.probeSelected(false) }.onFailure { Log.w(TAG, "probe selected failed", it) }
            } finally {
                probing = false
            }
        }
    }

    internal fun parse(json: String): List<ProxyGroupUi> {
        val arr = JSONArray(json)
        return List(arr.length()) { i ->
            val g = arr.getJSONObject(i)
            val ps = g.optJSONArray("proxies") ?: JSONArray()
            ProxyGroupUi(
                name = g.getString("name"),
                now = g.optString("now"),
                testURL = g.optString("testURL"),
                proxies = List(ps.length()) { j ->
                    val p = ps.getJSONObject(j)
                    val chain = p.optJSONArray("chain") ?: JSONArray()
                    val relays = p.optJSONArray("relays") ?: JSONArray()
                    ProxyUi(
                        name = p.getString("name"),
                        type = p.optString("type"),
                        chain = List(chain.length()) { chain.getString(it) },
                        delay = p.optInt("delay", -1),
                        relays = List(relays.length()) { relays.getString(it) },
                        addr = p.optString("addr"),
                    )
                },
            )
        }
    }
}
