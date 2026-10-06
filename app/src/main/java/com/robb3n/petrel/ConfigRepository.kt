package com.robb3n.petrel

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import com.robb3n.petrel.core.ptcore.Ptcore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 当前配置的显示信息。有导入元数据时 [imported] 为 true，[timeMillis] 是导入时间；
 * 没有（例如经 scripts/push-config.sh 推入）时名字固定为 config.yaml，[timeMillis] 是文件修改时间。
 */
data class ConfigInfo(val present: Boolean, val name: String, val timeMillis: Long, val imported: Boolean) {
    companion object {
        val ABSENT = ConfigInfo(present = false, name = ConfigRepository.CONFIG_FILE, timeMillis = 0, imported = false)
    }
}

/** 一次导入失败的横幅内容：标题固定，错误框是等宽原文，说明行可为空。 */
data class ImportError(val box: String, val hint: String = "") {
    val title get() = "导入失败，现有配置没动"
}

data class ImportUi(val busy: Boolean = false, val error: ImportError? = null)

/** Country.mmdb 的状态；null（尚未算出）时界面显示 —。 */
enum class GeoIpStatus { Ready, Corrupt, Missing }

/**
 * 配置与 GeoIP 库的导入：先校验，通过才替换；失败时现有文件一律不动。
 * 配置含节点凭据：只存在于 filesDir，内容与 mihomo 的校验报错都不进日志。
 */
object ConfigRepository {
    const val CONFIG_FILE = "config.yaml"
    private const val META_FILE = "config.meta.json"
    private const val MMDB_DIR = "mihomo"
    private const val MMDB_FILE = "Country.mmdb"
    private const val MAX_CONFIG_BYTES = 2L * 1024 * 1024
    private const val MAX_GEOIP_BYTES = 64L * 1024 * 1024
    private const val VALIDATE_TIMEOUT_MS = 30_000L

    // 校验与读文件都可能耗时，且 Go 侧不可取消：放在 IO 线程，不绑 Activity 生命周期（旋转屏幕不丢进度）
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private val _info = MutableStateFlow(ConfigInfo.ABSENT)
    val info: StateFlow<ConfigInfo> = _info
    private val _import = MutableStateFlow(ImportUi())
    val import: StateFlow<ImportUi> = _import
    private val _geo = MutableStateFlow<GeoIpStatus?>(null)
    val geo: StateFlow<GeoIpStatus?> = _geo
    private val _geoBusy = MutableStateFlow(false)
    val geoBusy: StateFlow<Boolean> = _geoBusy

    /** 重新读 config.yaml 与元数据；配置可能被 push-config.sh 在界面之外改掉，回到前台时要调。 */
    fun refresh(ctx: Context) {
        _info.value = readInfo(ctx.applicationContext)
    }

    /** 在 IO 线程算 GeoIP 库状态，进入配置页时调用。 */
    fun refreshGeo(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { _geo.value = geoStatus(app) }
    }

    /** 横幅一直显示，直到下一次导入开始或离开配置页。 */
    fun clearError() {
        _import.value = _import.value.copy(error = null)
    }

    /** [uri] 为 null 表示选择器取消，什么也不做。 */
    fun importConfig(ctx: Context, uri: Uri?) {
        if (uri == null) return
        val app = ctx.applicationContext
        if (!startImport()) return
        scope.launch {
            val error = try {
                doImport(app, uri)
            } catch (e: Exception) {
                PLog.w("import failed: ${e.javaClass.simpleName}") // 异常信息可能带路径或内容，不记
                ImportError("保存失败：${e.message}")
            }
            _import.value = ImportUi(busy = false, error = error)
        }
    }

    private fun startImport(): Boolean {
        synchronized(this) {
            if (_import.value.busy) return false
            _import.value = ImportUi(busy = true)
            return true
        }
    }

    /** 成功返回 null，失败返回横幅内容。 */
    private suspend fun doImport(app: Context, uri: Uri): ImportError? {
        val name = displayName(app, uri) ?: CONFIG_FILE

        val bytes = try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                ByteArrayOutputStream().also { copyLimited(input, it, MAX_CONFIG_BYTES) }.toByteArray()
            } ?: throw IOException("无法打开")
        } catch (e: TooLarge) {
            return ImportError("$name：文件超过 2 MB，不像 mihomo 配置")
        } catch (e: IOException) {
            return ImportError("读不了这个文件：${e.message}")
        } catch (e: SecurityException) {
            return ImportError("读不了这个文件：${e.message}")
        }

        // Go 侧不可取消：超时只是不再等它，它会自己跑完，结果丢弃
        val validation = scope.async { Ptcore.validateConfig(app.filesDir.absolutePath, bytes) }
        val verdict = withTimeoutOrNull(VALIDATE_TIMEOUT_MS) { validation.await() }
            ?: return ImportError("$name：校验超时", "可能在下载 GeoIP 库。先在下方替换 GeoIP 库，再导入一次。")
        if (verdict.isNotEmpty()) {
            return ImportError("$name：$verdict", "这是 mihomo 校验时报的原文。改好后再导入一次。")
        }

        try {
            saveConfig(app, bytes, name)
        } catch (e: IOException) {
            PLog.w("save config failed: ${e.javaClass.simpleName}")
            return ImportError("保存失败：${e.message}")
        }
        refresh(app)

        val restart = CoreBridge.state.value.active
        toast(app, if (restart) "已导入 $name，正在重启 VPN" else "已导入 $name")
        if (restart) PetrelVpnService.restart(app)
        return null
    }

    /** 先写两个 tmp 并 sync，再依次 rename 覆盖；任何一步失败都删掉 tmp，旧文件不动。 */
    private fun saveConfig(app: Context, bytes: ByteArray, name: String) {
        val dir = app.filesDir
        val cfgTmp = File(dir, "$CONFIG_FILE.tmp")
        val metaTmp = File(dir, "$META_FILE.tmp")
        val meta = JSONObject().put("name", name).put("importedAt", System.currentTimeMillis()).put("size", bytes.size)
        try {
            writeSynced(cfgTmp, bytes)
            writeSynced(metaTmp, meta.toString().toByteArray())
            renameOver(cfgTmp, File(dir, CONFIG_FILE))
        } catch (e: IOException) {
            cfgTmp.delete()
            metaTmp.delete()
            throw e
        }
        try {
            renameOver(metaTmp, File(dir, META_FILE))
        } catch (e: IOException) {
            // 配置已经换上了；旧元数据对不上新配置，删掉，界面退回显示 config.yaml 与文件时间
            metaTmp.delete()
            File(dir, META_FILE).delete()
            PLog.w("save meta failed: ${e.javaClass.simpleName}")
        }
    }

    /** [uri] 为 null 表示选择器取消。 */
    fun replaceGeoIp(ctx: Context, uri: Uri?) {
        if (uri == null) return
        val app = ctx.applicationContext
        synchronized(this) {
            if (_geoBusy.value) return
            _geoBusy.value = true
        }
        scope.launch {
            try {
                toast(app, doReplaceGeoIp(app, uri))
            } catch (e: Exception) {
                PLog.w("replace geoip failed: ${e.javaClass.simpleName}")
                toast(app, "保存失败：${e.message}")
            } finally {
                _geo.value = geoStatus(app)
                _geoBusy.value = false
            }
        }
    }

    /** 返回要给用户看的 Toast 文案。 */
    private fun doReplaceGeoIp(app: Context, uri: Uri): String {
        val dir = File(app.filesDir, MMDB_DIR).apply { mkdirs() }
        val tmp = File(dir, "$MMDB_FILE.tmp")
        try {
            try {
                val stream = app.contentResolver.openInputStream(uri) ?: throw IOException("无法打开")
                stream.use { input ->
                    FileOutputStream(tmp).use {
                        copyLimited(input, it, MAX_GEOIP_BYTES)
                        it.fd.sync()
                    }
                }
            } catch (e: TooLarge) {
                tmp.delete()
                return "不是有效的 GeoIP 库"
            } catch (e: IOException) {
                tmp.delete()
                return "读不了这个文件：${e.message}"
            } catch (e: SecurityException) {
                tmp.delete()
                return "读不了这个文件：${e.message}"
            }
            // 必须先校验再替换：mihomo 加载 mmdb 失败会 log.Fatalln，直接杀掉进程、VPN 随之断开
            if (!Ptcore.validateGeoIP(tmp.absolutePath)) {
                tmp.delete()
                return "不是有效的 GeoIP 库"
            }
            renameOver(tmp, File(dir, MMDB_FILE))
        } catch (e: IOException) {
            tmp.delete()
            return "保存失败：${e.message}"
        }
        Ptcore.reloadGeoIP()
        if (CoreBridge.state.value.active) PetrelVpnService.restart(app)
        return "已替换 GeoIP 库"
    }

    private fun readInfo(app: Context): ConfigInfo {
        val f = File(app.filesDir, CONFIG_FILE)
        if (!f.exists()) return ConfigInfo.ABSENT
        val meta = runCatching { JSONObject(File(app.filesDir, META_FILE).readText()) }.getOrNull()
        val name = meta?.optString("name").orEmpty()
        val at = meta?.optLong("importedAt", 0L) ?: 0L
        return if (meta != null && name.isNotEmpty() && at > 0) {
            ConfigInfo(present = true, name = name, timeMillis = at, imported = true)
        } else {
            ConfigInfo(present = true, name = CONFIG_FILE, timeMillis = f.lastModified(), imported = false)
        }
    }

    private fun geoStatus(app: Context): GeoIpStatus {
        val f = File(app.filesDir, "$MMDB_DIR/$MMDB_FILE")
        return when {
            !f.exists() -> GeoIpStatus.Missing
            Ptcore.validateGeoIP(f.absolutePath) -> GeoIpStatus.Ready
            else -> GeoIpStatus.Corrupt
        }
    }

    private fun displayName(app: Context, uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    private class TooLarge : IOException()

    /** 把 [input] 复制到 [out]，最多 [limit] 字节，多一个字节就抛 [TooLarge]。 */
    private fun copyLimited(input: InputStream, out: OutputStream, limit: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw TooLarge()
            out.write(buf, 0, n)
        }
    }

    private fun writeSynced(f: File, bytes: ByteArray) {
        FileOutputStream(f).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    private fun renameOver(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("重命名 ${to.name} 失败")
    }

    private fun toast(app: Context, text: String) {
        main.post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
    }
}
