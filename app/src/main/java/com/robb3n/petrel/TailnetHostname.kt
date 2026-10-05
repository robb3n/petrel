package com.robb3n.petrel

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 本机在 tailnet 里的节点名：首次注册时由机型生成（如 `pjd110-petrel`），存在 `files/tailnet-hostname`，之后一直沿用。
 * 已有 tsnet 状态、却没有这个文件的安装（节点名还写死在代码里时注册的）沿用当时的 `op12-petrel`，不改已注册节点的名字。
 * 见 docs/lessons/network-change.md「节点名」。
 */
object TailnetHostname {
    private const val FILE = "tailnet-hostname"
    private const val LEGACY = "op12-petrel"

    @Volatile private var cached: String? = null

    /** 读文件（首次还会写），量很小；主线程调用也可以。 */
    @Synchronized
    fun get(ctx: Context): String {
        cached?.let { return it }
        val files = ctx.filesDir
        val f = File(files, FILE)
        val stored = runCatching { f.readText().trim() }.getOrNull().orEmpty()
        val name = stored.ifEmpty {
            val registered = File(files, "tsnet").list()?.isNotEmpty() == true
            (if (registered) LEGACY else fromModel(Build.MODEL)).also { persist(files, it) }
        }
        cached = name
        return name
    }

    /**
     * 先写临时文件再改名：写到一半被杀不会留下空文件（空文件 + 已有 tsnet 状态会被当成老安装，名字被改回 op12-petrel）。
     * 写不进去（磁盘满）也不抛：这一次照样用算出来的名字，下次再写。
     */
    private fun persist(dir: File, name: String) {
        runCatching {
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(name)
            check(tmp.renameTo(File(dir, FILE)))
        }
    }

    /** 机型规范成主机名：小写、非字母数字换成 `-`、去掉首尾与连续的 `-`、截到 40 个字符，再加 `-petrel`。 */
    fun fromModel(model: String?): String {
        val base = model.orEmpty().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .trimEnd('-')
        return "${base.ifEmpty { "android" }}-petrel"
    }
}
