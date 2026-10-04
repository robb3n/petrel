package com.robb3n.petrel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/** 复制到系统剪贴板并弹 Toast（调用方在主线程）。[label] 只是剪贴板条目的描述，不显示给人。 */
fun copyToClipboard(ctx: Context, label: String, text: String, toast: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
}
