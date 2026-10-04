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
)

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
        val before = _groups.value
        _groups.value = before.map { if (it.name == group) it.copy(now = name) else it }
        scope.launch {
            runCatching { Ptcore.selectProxy(group, name) }.onFailure {
                Log.w(TAG, "select failed", it)
                // 只回滚这一组的选中项，别覆盖期间别处刷新进来的延迟
                _groups.value = _groups.value.map { g ->
                    val old = before.firstOrNull { b -> b.name == group }
                    if (g.name == group && old != null) g.copy(now = old.now) else g
                }
                onError(it.message ?: "未知错误")
            }
        }
    }

    /** 测所有组的延迟，进行中 [testing] 为 true。 */
    fun testDelay() {
        if (!_testing.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                runCatching { Ptcore.testGroupDelay("") }.onFailure { Log.w(TAG, "test delay failed", it) }
            } finally {
                _testing.value = false
            }
        }
    }

    /** 首页的刷新：预热 tailnet 第一跳 + 测所有组（与 [testDelay] 共用 [testing]），再重拉 tailnet 节点列表。 */
    fun refresh() {
        if (!_testing.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                runCatching { Ptcore.refresh() }.onFailure { Log.w(TAG, "refresh failed", it) }
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
                    )
                },
            )
        }
    }
}
