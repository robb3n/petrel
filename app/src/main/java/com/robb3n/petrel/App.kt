package com.robb3n.petrel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PLog.init(this, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        installCrashLogger()
        if (Build.VERSION.SDK_INT >= 30) thread(name = "petrel-exit-reasons", isDaemon = true) { logExitReasons() }
        instance = this
        Notifications.ensureChannel(this)
        GroupsRepository.start()
        ExitIpRepository.start(this)
    }

    /** Java 层崩溃先同步写进日志文件，再交回原来的 handler，让系统照常记 FATAL EXCEPTION。 */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { PLog.fatal(t, e) }
            previous?.uncaughtException(t, e)
        }
    }

    /**
     * 上次以来系统记下的进程死因（被杀时 App 来不及自己打日志）。按 `lastExitTs` 去重，时间正序写，崩溃类记 W。
     * 不读 getTraceInputStream：ANR trace 太大，而且可能带内容。
     */
    private fun logExitReasons() {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val prefs = getSharedPreferences("petrel_log", Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_LAST_EXIT_TS, Long.MIN_VALUE)
            val fresh = getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 5)
                .filter { it.timestamp > last }
                .sortedBy { it.timestamp }
            if (fresh.isEmpty()) return
            fresh.forEach {
                val line = formatExitLine(it.reason, it.timestamp, it.importance, it.pss, it.rss, it.description)
                if (exitReasonIsCrash(it.reason)) PLog.w(line) else PLog.i(line)
            }
            prefs.edit().putLong(KEY_LAST_EXIT_TS, fresh.last().timestamp).apply()
        }.onFailure { PLog.w("exit reasons unreadable: ${it.javaClass.simpleName}") }
    }

    companion object {
        private const val KEY_LAST_EXIT_TS = "lastExitTs"

        @Volatile
        var instance: App? = null
            private set
    }
}
