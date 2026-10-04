package com.robb3n.petrel

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 皮肤。持久化值见 [key]；设置页只列已经实现的皮肤，见 `ui/skin/PetrelSkin.kt` 的 `IMPLEMENTED_SKINS`。 */
enum class Skin(val key: String, val title: String) {
    Shoal("shoal", "Shoal"),
    Night("night", "夜航"),
    Tonal("tonal", "Tonal");

    companion object {
        /** 读到未知值一律当默认值。 */
        fun fromKey(key: String?): Skin = entries.firstOrNull { it.key == key } ?: Shoal
    }
}

/** 明暗设置：跟随系统 / 浅 / 深。 */
enum class UiTone(val key: String, val title: String) {
    Auto("auto", "跟随系统"),
    Light("light", "浅"),
    Dark("dark", "深");

    companion object {
        fun fromKey(key: String?): UiTone = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/** 应用内容实际的明暗：AUTO 跟系统，LIGHT 恒浅，DARK 恒深。窗口底色、系统栏图标色、界面都按这一个函数判。 */
fun resolveDark(tone: UiTone, systemDark: Boolean): Boolean = when (tone) {
    UiTone.Auto -> systemDark
    UiTone.Light -> false
    UiTone.Dark -> true
}

/**
 * 界面偏好，SharedPreferences 文件 `ui`：`skin`、`tone` 两个键。
 * 进程里第一次访问时同步读一次（文件很小），保证第一帧就是对的皮肤与明暗，不闪；写入用 `apply()`。
 */
object UiPrefs {
    private const val FILE = "ui"
    private const val KEY_SKIN = "skin"
    private const val KEY_TONE = "tone"

    private val prefs: SharedPreferences by lazy {
        val app = checkNotNull(App.instance) { "UiPrefs read before Application.onCreate" }
        app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private val _skin: MutableStateFlow<Skin> by lazy { MutableStateFlow(Skin.fromKey(prefs.getString(KEY_SKIN, null))) }
    private val _tone: MutableStateFlow<UiTone> by lazy { MutableStateFlow(UiTone.fromKey(prefs.getString(KEY_TONE, null))) }

    val skin: StateFlow<Skin> get() = _skin
    val tone: StateFlow<UiTone> get() = _tone

    fun setSkin(skin: Skin) {
        _skin.value = skin
        prefs.edit().putString(KEY_SKIN, skin.key).apply()
    }

    fun setTone(tone: UiTone) {
        _tone.value = tone
        prefs.edit().putString(KEY_TONE, tone.key).apply()
    }
}
