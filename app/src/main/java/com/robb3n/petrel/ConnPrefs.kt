package com.robb3n.petrel

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 连接模式：设置里 tailnet、代理两个开关的组合，至少开一个。[key] 是传给 `Ptcore.start` 的值（Go 侧 `Mode*` 常量）。
 * - [Both]：tailnet 是代理链的第一跳，全部流量按配置分流（默认）。
 * - [Tailnet]：只接管 tailnet 地址，其它流量不进 VPN；不需要配置。
 * - [Proxy]：不起 tailnet，配置里经 `ts` 的节点不可用。
 */
enum class ConnMode(val key: String, val title: String) {
    Both("both", "tailnet + 代理"),
    Tailnet("tailnet", "仅 tailnet"),
    Proxy("proxy", "仅代理");

    val tailnetOn: Boolean get() = this != Proxy
    val proxyOn: Boolean get() = this != Tailnet

    companion object {
        /** 两个开关都关是不允许的状态，按默认值处理。 */
        fun of(tailnet: Boolean, proxy: Boolean): ConnMode = when {
            tailnet && proxy -> Both
            tailnet -> Tailnet
            proxy -> Proxy
            else -> Both
        }

        /** Go 推来的 `mode`；停止时是空串，返回 null。 */
        fun fromKey(key: String?): ConnMode? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 连接偏好，SharedPreferences 文件 `conn`：`tailnet`、`proxy` 两个开关，默认都开。
 * 服务每次启动时读 [mode]；VPN 开着时改了开关，由调用方让服务重启生效（[PetrelVpnService.restart]）。
 */
object ConnPrefs {
    private const val FILE = "conn"
    private const val KEY_TAILNET = "tailnet"
    private const val KEY_PROXY = "proxy"

    private val prefs: SharedPreferences by lazy {
        val app = checkNotNull(App.instance) { "ConnPrefs read before Application.onCreate" }
        app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private val _mode: MutableStateFlow<ConnMode> by lazy {
        MutableStateFlow(ConnMode.of(prefs.getBoolean(KEY_TAILNET, true), prefs.getBoolean(KEY_PROXY, true)))
    }

    val mode: StateFlow<ConnMode> get() = _mode

    /** 关掉最后一个开着的开关不生效，返回 false。 */
    fun setTailnet(on: Boolean): Boolean = set(on, _mode.value.proxyOn)

    fun setProxy(on: Boolean): Boolean = set(_mode.value.tailnetOn, on)

    private fun set(tailnet: Boolean, proxy: Boolean): Boolean {
        if (!tailnet && !proxy) return false
        val next = ConnMode.of(tailnet, proxy)
        if (next == _mode.value) return false
        _mode.value = next
        prefs.edit().putBoolean(KEY_TAILNET, tailnet).putBoolean(KEY_PROXY, proxy).apply()
        return true
    }
}
