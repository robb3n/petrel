package com.robb3n.petrel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 指向同一个组件的 PendingIntent 只按 intent 的 action / data / 组件 + requestCode 区分，extras 不算：
 * 撞键时 FLAG_UPDATE_CURRENT 会把一处的 extras 写进另一处（磁贴的 EXTRA_START 曾因此让点通知悄悄起 VPN）。
 * 所以每个入口各用一个 requestCode。
 */
object RequestCodes {
    const val NOTIFICATION_OPEN = 0
    const val VPN_CONFIGURE = 1
    const val TILE_CONSENT = 2
    const val TILE_LAUNCH = 3
}

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
            ctx, RequestCodes.NOTIFICATION_OPEN, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
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

    private fun needsLogin(s: CoreState) = s.vpn == "running" && s.mode != ConnMode.Proxy && s.tailnet == TAILNET_NEEDS_LOGIN

    fun title(s: CoreState): String = when {
        s.vpn == "starting" -> "连接中"
        needsLogin(s) -> "需要登录 tailnet"
        else -> "已连接"
    }

    fun body(s: CoreState): String? {
        val exit = if (s.exit.isEmpty()) null else "出口 ${s.exit}"
        return when {
            s.vpn == "starting" -> null
            needsLogin(s) -> if (s.mode == ConnMode.Tailnet) "登录之后才能访问 tailnet" else "登录之前代理用不了"
            s.mode == ConnMode.Proxy -> listOfNotNull("仅代理", exit).joinToString(" · ")
            s.tailnet == TAILNET_RUNNING -> {
                val tailnet = "tailnet ${s.tailnetIPs.firstOrNull() ?: ""}".trimEnd()
                when {
                    s.mode == ConnMode.Tailnet -> "仅 $tailnet"
                    exit == null -> tailnet
                    else -> "$exit · $tailnet"
                }
            }
            else -> "tailnet ${s.tailnet}"
        }
    }
}
