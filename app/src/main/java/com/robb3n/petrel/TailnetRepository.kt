package com.robb3n.petrel

import com.robb3n.petrel.core.ptcore.Ptcore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** tsnet 的 BackendState 字符串，见 ipn.State。 */
const val TAILNET_RUNNING = "Running"
const val TAILNET_NEEDS_LOGIN = "NeedsLogin"

/** 对端路径，见 core/ptcore/tailnet.go 的 peerPath。 */
enum class PeerPath { Direct, Relay, PeerRelay, Idle, Offline }

data class TailnetSelfUi(val name: String, val ips: List<String>)

data class TailnetPeerUi(
    val name: String,
    val ips: List<String>,
    val os: String,
    val online: Boolean,
    val path: PeerPath,
    /** DERP 区域代码，只有 [PeerPath.Relay] 时非空 */
    val relay: String,
)

data class TailnetStatusUi(val self: TailnetSelfUi, val peers: List<TailnetPeerUi>) {
    val onlineCount get() = peers.count { it.online }
}

/** 第一个 IPv4（不含冒号）；只有 IPv6 或没有地址时为 null。 */
fun List<String>.firstIPv4(): String? = firstOrNull { ':' !in it }

/**
 * tailnet 面板的数据：[TailnetRepository.refresh] 由界面在标签可见时每 5 秒调一次；
 * tailnet 不在运行（VPN 停了、待登录）时清空，免得下次进入时先闪一下旧数据。
 */
object TailnetRepository {
    private val _status = MutableStateFlow<TailnetStatusUi?>(null)
    val status: StateFlow<TailnetStatusUi?> = _status

    /**
     * 清空与写回在同一把锁里串行，[generation] 每清空一次加 1：拉取期间清空过（停 VPN、登出，哪怕之后又连上），
     * 这次拉到的就是上一段的数据，不写回。
     */
    private val lock = Any()
    private var generation = 0

    /**
     * 由 [CoreBridge] 在每次状态变化时同步调用（不经 StateFlow 收集：收集会合并中间值，
     * 很快的「断开 → 又连上」可能整个被跳过，代次就不会推进）。tailnet 不在运行时清空并推进代次。
     */
    fun onState(s: CoreState) {
        if (s.tailnetLive) return
        synchronized(lock) {
            generation++
            _status.value = null
        }
    }

    /** 拉一次状态。取不到（还没就绪返回 "{}"）时清空；调用本身失败时保留上一次的数据。 */
    suspend fun refresh() {
        val gen = synchronized(lock) { generation }
        val json = withContext(Dispatchers.IO) {
            runCatching { Ptcore.tailnetStatus() }.onFailure { PLog.w("tailnet status failed", it) }.getOrNull()
        } ?: return
        val parsed = parse(json)
        synchronized(lock) {
            // 期间 VPN 可能已经停了、或已经登出：别把旧数据写回去（条件与上面清空用的是同一个）
            if (gen != generation || !CoreBridge.state.value.tailnetLive) return
            _status.value = parsed
        }
    }

    /** 登出 tailnet，失败抛异常。 */
    suspend fun logout() = withContext(Dispatchers.IO) { Ptcore.logout() }

    internal fun parse(json: String): TailnetStatusUi? = runCatching {
        val o = JSONObject(json)
        val self = o.optJSONObject("self") ?: return@runCatching null
        val peers = o.optJSONArray("peers")
        TailnetStatusUi(
            self = TailnetSelfUi(self.optString("name"), strings(self.optJSONArray("ips"))),
            peers = List(peers?.length() ?: 0) { i ->
                val p = peers!!.getJSONObject(i)
                TailnetPeerUi(
                    name = p.optString("name"),
                    ips = strings(p.optJSONArray("ips")),
                    os = p.optString("os"),
                    online = p.optBoolean("online"),
                    path = when (p.optString("path")) {
                        "direct" -> PeerPath.Direct
                        "relay" -> PeerPath.Relay
                        "peer-relay" -> PeerPath.PeerRelay
                        "offline" -> PeerPath.Offline
                        else -> PeerPath.Idle
                    },
                    relay = p.optString("relay"),
                )
            },
        )
    }.getOrElse {
        PLog.w("bad tailnet status json", it)
        null
    }

    private fun strings(a: org.json.JSONArray?): List<String> = List(a?.length() ?: 0) { a!!.getString(it) }
}
