package com.robb3n.petrel

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 快捷开关：App 冷启动（被强制停止、从没打开过）时按一下也要能直接起 VPN。
 * 未授权时拉主界面走一次系统授权；已授权时一律经 TileLaunchActivity 起服务：它留在 Petrel 的任务里，
 * 最近任务里才有卡片（直接 startForegroundService 虽然能冷启动，但不产生卡片）。
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
            Log.i(TAG, "tile: stop")
            PetrelVpnService.stop(this)
            return
        }
        if (VpnService.prepare(this) != null) {
            Log.i(TAG, "tile: vpn not prepared, opening app for consent")
            launch(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_START, true), RequestCodes.TILE_CONSENT)
            return
        }
        Log.i(TAG, "tile: launching TileLaunchActivity")
        // 任务还在最近任务里时，光带 NEW_TASK 只会把旧任务带回前台、不建新实例（ColorOS 上 VPN 因此不起）。
        // CLEAR_TOP + SINGLE_TOP：栈里有 TileLaunchActivity 就清掉它上面的界面并投递 onNewIntent，没有就在任务顶上新建。
        launch(
            Intent(this, TileLaunchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            RequestCodes.TILE_LAUNCH,
        )
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
            s.running && s.tailnet == "NeedsLogin" -> "待登录"
            s.running -> "已连接"
            else -> "未连接"
        }
        tile.updateTile()
    }
}
