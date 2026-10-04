package com.robb3n.petrel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifications {
    const val ID = 1
    private const val CHANNEL = "connection"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun build(ctx: Context, s: CoreState): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title(s))
            .setContentIntent(open)
            .setOngoing(true)
        body(s)?.let { b.setContentText(it) }
        if (needsLogin(s) && s.loginURL.isNotEmpty()) {
            b.addAction(Notification.Action.Builder(null, "复制登录链接", serviceAction(ctx, 2, PetrelVpnService.ACTION_COPY_LOGIN)).build())
        }
        b.addAction(Notification.Action.Builder(null, "断开", serviceAction(ctx, 1, PetrelVpnService.ACTION_STOP)).build())
        return b.build()
    }

    fun update(ctx: Context, s: CoreState) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, s))
    }

    private fun serviceAction(ctx: Context, requestCode: Int, action: String) = PendingIntent.getService(
        ctx, requestCode, Intent(ctx, PetrelVpnService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun needsLogin(s: CoreState) = s.vpn == "running" && s.tailnet == "NeedsLogin"

    fun title(s: CoreState): String = when {
        s.vpn == "starting" -> "连接中"
        needsLogin(s) -> "需要登录 tailnet"
        else -> "已连接"
    }

    fun body(s: CoreState): String? = when {
        s.vpn == "starting" -> null
        needsLogin(s) -> "登录之前代理用不了"
        s.tailnet == "Running" -> {
            val tailnet = "tailnet ${s.tailnetIPs.firstOrNull() ?: ""}".trimEnd()
            if (s.exit.isEmpty()) tailnet else "出口 ${s.exit} · $tailnet"
        }
        else -> "tailnet ${s.tailnet}"
    }
}
