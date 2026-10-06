package com.robb3n.petrel

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle

/**
 * 磁贴起 VPN 的入口：透明、无界面。前台 Activity 起服务，然后把任务退到后台但不 finish，
 * 让 Petrel 的任务留在最近任务里（可以上锁防杀）。任务回到前台时（点卡片、桌面图标）改为显示主界面，
 * 任务里已经没有界面、点卡片重建本 Activity 时也一样。
 *
 * 任务留着时再点磁贴，磁贴的请求经 onNewIntent 回到这个旧实例，同时也会走 onRestart。两者的先后顺序不固定，
 * 所以只做标记，等 onResume 再决定：磁贴又点了一下就起服务，否则是任务被带回前台，切到主界面。
 */
class TileLaunchActivity : Activity() {
    private var tileRequest = false
    private var restarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 任务里的界面都退出了、卡片还在时，点卡片会用任务的 base intent（就是本 Activity）重建，这时该显示主界面。
        // 带 savedInstanceState 的重建（进程被杀后点卡片、后台时切了明暗）系统不会标 LAUNCHED_FROM_HISTORY，也一样：
        // 磁贴的全新启动一定没有 savedInstanceState，不能把重建当成磁贴请求，否则悄悄打开了人刚关掉的 VPN
        if (savedInstanceState != null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
            showMain()
            return
        }
        startFromTile()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tileRequest = true
    }

    override fun onRestart() {
        super.onRestart()
        restarted = true
    }

    override fun onResume() {
        super.onResume()
        val tile = tileRequest
        val back = restarted
        tileRequest = false
        restarted = false
        when {
            tile -> startFromTile()
            back -> showMain()
        }
    }

    private fun showMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    private fun startFromTile() {
        if (VpnService.prepare(this) != null) {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_START, true))
            finish()
            return
        }
        PLog.i("tile launch: starting vpn from activity")
        PetrelVpnService.start(this)
        moveTaskToBack(true)
    }
}
