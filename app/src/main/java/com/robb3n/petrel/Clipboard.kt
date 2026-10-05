package com.robb3n.petrel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * 复制 tailnet 登录链接。登录完成后链接会被清空：空串不写进剪贴板，免得丢掉人原来的内容。
 * 链接本身不进日志。调用方在主线程。
 */
fun copyLoginUrl(ctx: Context) {
    val url = CoreBridge.state.value.loginURL
    if (url.isEmpty()) return
    copyToClipboard(ctx, "tailnet login", url, "已复制登录链接")
    Log.i(TAG, "copied tailnet login url")
}

/** 复制到系统剪贴板并弹 Toast（调用方在主线程）。[label] 只是剪贴板条目的描述，不显示给人。 */
fun copyToClipboard(ctx: Context, label: String, text: String, toast: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
}
