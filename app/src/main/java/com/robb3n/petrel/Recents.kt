package com.robb3n.petrel

import android.app.ActivityManager
import android.content.Context

/**
 * 「隐藏后台」：按 [UiPrefs.hideRecents] 把本 App 现有的任务标成进 / 不进最近任务。
 * 标记记在任务上，新建的任务不带，任务被桌面图标重新带回前台时系统也可能换掉它的 base intent，
 * 所以界面每次回到前台都重新落一遍（[MainActivity.onResume]），开关一动也立刻落。
 * 只动任务在最近任务里的可见性，不动服务：VPN 靠前台服务常驻，与卡片在不在无关。
 * ColorOS 上它自己防不了一键清理，要先锁定卡片再隐藏，见 `docs/lessons/tile-and-device-testing.md`。
 */
fun applyHideRecents(ctx: Context, hide: Boolean = UiPrefs.hideRecents.value) {
    val am = ctx.getSystemService(ActivityManager::class.java) ?: return
    runCatching { am.appTasks.forEach { it.setExcludeFromRecents(hide) } }
        .onFailure { PLog.w("recents: apply failed: ${it.javaClass.simpleName}") }
}
