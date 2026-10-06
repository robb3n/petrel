package com.robb3n.petrel

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 设置页「导出日志」：把 `files/logs/` 的两个文件加上头部拼成 `cache/export/petrel-<版本>-<时间>.log`，
 * 经 FileProvider（authority `<applicationId>.logs`）用系统分享面板发出去。release 包不能 run-as，只能靠这里取日志。
 */
object LogExport {
    private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private sealed interface Result {
        class Ready(val uri: Uri) : Result
        data object Empty : Result
        data object Failed : Result
    }

    /** 在调用方的协程里跑：拼文件走 IO，分享与 Toast 回主线程。[ctx] 是界面的 Context。 */
    suspend fun share(ctx: Context) {
        val result = withContext(Dispatchers.IO) { build(ctx.applicationContext) }
        when (result) {
            Result.Empty -> toast(ctx, "还没有日志")
            Result.Failed -> toast(ctx, "导出失败")
            is Result.Ready -> {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, result.uri)
                    clipData = ClipData.newRawUri("Petrel log", result.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "导出日志")
                if (ctx !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(chooser)
                } catch (_: ActivityNotFoundException) {
                    toast(ctx, "没有可以接收的应用")
                }
            }
        }
    }

    private fun build(app: Context): Result {
        PLog.flush(500)
        val dir = PLog.logDir(app)
        val parts = listOf(File(dir, LOG_BACKUP), File(dir, LOG_FILE)).filter { it.isFile && it.length() > 0 }
        if (parts.isEmpty()) return Result.Empty
        return try {
            val exportDir = File(app.cacheDir, "export")
            exportDir.deleteRecursively()
            exportDir.mkdirs()
            val now = System.currentTimeMillis()
            val out = File(exportDir, "petrel-${BuildConfig.VERSION_NAME}-${FILE_TIME.format(LocalDateTime.now())}.log")
            out.outputStream().buffered().use { o ->
                val header = buildString {
                    appendLine("Petrel v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("mode=${ConnPrefs.mode.value.key} vpn=${CoreBridge.state.value.vpn}")
                    appendLine("exported ${formatSecond(now)}")
                    appendLine()
                }
                o.write(header.toByteArray(Charsets.UTF_8))
                parts.forEach { f -> f.inputStream().use { it.copyTo(o) } }
            }
            Result.Ready(FileProvider.getUriForFile(app, "${app.packageName}.logs", out))
        } catch (e: Exception) {
            PLog.w("log export failed: ${e.javaClass.simpleName}")
            Result.Failed
        }
    }

    private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
}
