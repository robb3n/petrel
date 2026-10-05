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

/** 错误文本可能是 mihomo 的配置解析错误，带着配置行与凭据：日志里只记「有错误」。JSON 字符串里的转义引号也要跨过去。 */
private val ERROR_VALUE = Regex("\"error\":\"(?:[^\"\\\\]|\\\\.)+\"")

/** 状态 JSON 的日志形态：登录链接与错误文本都换成 `<set>`。 */
internal fun redactStateJson(json: String): String =
    json.replace(LOGIN_URL_VALUE, "\"loginURL\":\"<set>\"").replace(ERROR_VALUE, "\"error\":\"<set>\"")

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
            // 不记原文与异常信息：里面有完整登录链接，JSONException 的信息也会引用原文
            Log.w(TAG, "bad state json: ${it.javaClass.simpleName}")
            STOPPED
        }
    }
}

/** Go 内核的 Host 实现：状态进 StateFlow 并刷新磁贴，日志进 logcat（tag Petrel）。 */
object CoreBridge : Host {
    private val _state = MutableStateFlow(CoreState.STOPPED)
    val state: StateFlow<CoreState> = _state

    override fun onState(json: String) {
        Log.i(TAG, "state ${redactStateJson(json)}") // 登录链接与错误文本不进日志
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

    /**
     * Kotlin 侧在进 Go 之前就失败、或 Go 的 Start 抛错时用它报错。[message] 完整显示给人；
     * [logLine] 进日志，带了异常信息（可能含配置内容）时调用方要给一个不含它的版本。
     */
    fun fail(message: String, logLine: String = message) {
        Log.e(TAG, logLine)
        set(CoreState.STOPPED.copy(error = message))
    }

    fun markStarting() = set(_state.value.copy(vpn = "starting", error = ""))

    private fun set(s: CoreState) {
        _state.value = s
        TailnetRepository.onState(s)
        App.instance?.let { ctx ->
            TileService.requestListeningState(ctx, ComponentName(ctx, PetrelTileService::class.java))
        }
    }
}
