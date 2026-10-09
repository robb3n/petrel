package com.robb3n.petrel

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 快捷开关：App 冷启动（被强制停止、从没打开过）时按一下也要能直接起 VPN。
 * 未授权时拉主界面走一次系统授权；已授权时直接起前台服务，保持快捷设置面板与底下的 App 不变。
 * 不为创建最近任务卡片而启动 Activity，也不在后台启动被系统拒绝时悄悄收起面板。
 */
class PetrelTileService : TileService() {
    private val scope = MainScope()
    private var watch: Job? = null

    // 面板展开期间磁贴一直在监听，requestListeningState 不会再触发 onStartListening，所以监听期间直接订阅状态
    override fun onStartListening() {
        watch?.cancel()
        watch = scope.launch { CoreBridge.state.collect { refresh() } }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        val s = CoreBridge.state.value
        if (s.active) {
            PLog.i("tile: stop")
            PetrelVpnService.stop(this)
            return
        }
        if (VpnService.prepare(this) != null) {
            PLog.i("tile: vpn not prepared, opening app for consent")
            launch(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_START, true), RequestCodes.TILE_CONSENT)
            return
        }
        PLog.i("tile: starting vpn service directly")
        try {
            PetrelVpnService.start(this)
        } catch (e: RuntimeException) {
            CoreBridge.fail("无法后台启动，请打开 Petrel 后连接", "tile start failed: ${e.javaClass.simpleName}")
        }
    }

    private fun launch(intent: Intent, requestCode: Int) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val s = CoreBridge.state.value
        tile.label = getString(R.string.app_name)
        // 显式设图标：只靠 manifest 的 android:icon 时，SystemUI（至少 ColorOS）覆盖安装后仍沿用缓存的旧图标
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.state = if (s.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            s.vpn == "starting" -> "连接中"
            s.running && s.mode != ConnMode.Proxy && s.tailnet == TAILNET_NEEDS_LOGIN -> "待登录"
            s.running -> "已连接"
            else -> "未连接"
        }
        tile.updateTile()
    }
}
