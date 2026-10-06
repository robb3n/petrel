package com.robb3n.petrel

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 明暗设置：跟随系统 / 浅 / 深。 */
enum class UiTone(val key: String, val title: String) {
    Auto("auto", "跟随系统"),
    Light("light", "浅"),
    Dark("dark", "深");

    companion object {
        fun fromKey(key: String?): UiTone = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/** 连接页的出口 IP 放在哪：状态卡里一行 / 独立卡片 / 当前链路的出口那一跳下面。 */
enum class ExitIpPlace(val key: String, val title: String, val desc: String) {
    Hero("hero", "状态卡里", "状态卡里一行：国家、IP、城市。最省地方。"),
    Card("card", "独立卡片", "单独一张卡：IP、位置、运营商、IP 类型。信息最全。"),
    Chain("chain", "链路末跳", "写在当前链路的出口那一跳下面，和节点页一样。");

    companion object {
        fun fromKey(key: String?): ExitIpPlace = entries.firstOrNull { it.key == key } ?: Card
    }
}

/** 应用内容实际的明暗：AUTO 跟系统，LIGHT 恒浅，DARK 恒深。窗口底色、系统栏图标色、界面都按这一个函数判。 */
fun resolveDark(tone: UiTone, systemDark: Boolean): Boolean = when (tone) {
    UiTone.Auto -> systemDark
    UiTone.Light -> false
    UiTone.Dark -> true
}

/**
 * 界面偏好，SharedPreferences 文件 `ui`：`tone`、`exit_ip` 两个键（旧版的 `skin` 键已不再读）。
 * 进程里第一次访问时同步读一次（文件很小），保证第一帧就是对的明暗，不闪；写入用 `apply()`。
 */
object UiPrefs {
    private const val FILE = "ui"
    private const val KEY_TONE = "tone"
    private const val KEY_EXIT_IP = "exit_ip"

    private val prefs: SharedPreferences by lazy {
        val app = checkNotNull(App.instance) { "UiPrefs read before Application.onCreate" }
        app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private val _tone: MutableStateFlow<UiTone> by lazy { MutableStateFlow(UiTone.fromKey(prefs.getString(KEY_TONE, null))) }
    private val _exitIp: MutableStateFlow<ExitIpPlace> by lazy {
        MutableStateFlow(ExitIpPlace.fromKey(prefs.getString(KEY_EXIT_IP, null)))
    }

    val tone: StateFlow<UiTone> get() = _tone
    val exitIp: StateFlow<ExitIpPlace> get() = _exitIp

    fun setTone(tone: UiTone) {
        _tone.value = tone
        prefs.edit().putString(KEY_TONE, tone.key).apply()
    }

    fun setExitIp(place: ExitIpPlace) {
        _exitIp.value = place
        prefs.edit().putString(KEY_EXIT_IP, place.key).apply()
    }
}
