package com.robb3n.petrel

import android.content.ComponentName
import android.service.quicksettings.TileService
import android.util.Log
import com.robb3n.petrel.core.ptcore.Host
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

const val TAG = "Petrel"
private val LOGIN_URL_VALUE = Regex("\"loginURL\":\"[^\"]+\"")

/** Go 内核推来的状态，形状见 core/ptcore/core.go 的 state。 */
data class CoreState(
    val vpn: String,
    val tailnet: String,
    val tailnetIPs: List<String>,
    val loginURL: String,
    val error: String,
    /** 第一个可切换组当前选中的节点名，没有组时为空串。 */
    val exit: String = "",
    /** 组数据（选中项或延迟）每变一次加 1，据此重新拉 Groups()。 */
    val groupsRev: Int = 0,
) {
    val running get() = vpn == "running"
    val active get() = vpn == "running" || vpn == "starting"

    /** tailnet 面板的数据此刻有意义：VPN 在跑且不是待登录。进入时拉取、离开时清空、写回前复查都用它。 */
    val tailnetLive get() = running && tailnet != TAILNET_NEEDS_LOGIN

    companion object {
        val STOPPED = CoreState("stopped", "NoState", emptyList(), "", "")

        fun parse(json: String): CoreState = runCatching {
            val o = JSONObject(json)
            val ips = o.optJSONArray("tailnetIPs")
            CoreState(
                vpn = o.optString("vpn", "stopped"),
                tailnet = o.optString("tailnet", "NoState"),
                tailnetIPs = List(ips?.length() ?: 0) { ips!!.getString(it) },
                loginURL = o.optString("loginURL"),
                error = o.optString("error"),
                exit = o.optString("exit"),
                groupsRev = o.optInt("groupsRev"),
            )
        }.getOrElse {
            Log.w(TAG, "bad state json: $json", it)
            STOPPED
        }
    }
}

/** Go 内核的 Host 实现：状态进 StateFlow 并刷新磁贴，日志进 logcat（tag Petrel）。 */
object CoreBridge : Host {
    private val _state = MutableStateFlow(CoreState.STOPPED)
    val state: StateFlow<CoreState> = _state

    override fun onState(json: String) {
        Log.i(TAG, "state ${json.replace(LOGIN_URL_VALUE, "\"loginURL\":\"<set>\"")}") // 登录链接不进日志
        set(CoreState.parse(json))
    }

    override fun log(level: String, msg: String) {
        when (level) {
            "debug" -> Log.d(TAG, msg)
            "warn", "warning" -> Log.w(TAG, msg)
            "error" -> Log.e(TAG, msg)
            else -> Log.i(TAG, msg)
        }
    }

    /** Kotlin 侧在进 Go 之前就失败时（没配置、没授权）用它报错。 */
    fun fail(message: String) {
        Log.e(TAG, message)
        set(CoreState.STOPPED.copy(error = message))
    }

    fun markStarting() = set(_state.value.copy(vpn = "starting", error = ""))

    private fun set(s: CoreState) {
        _state.value = s
        App.instance?.let { ctx ->
            TileService.requestListeningState(ctx, ComponentName(ctx, PetrelTileService::class.java))
        }
    }
}
