package com.robb3n.petrel.ui.skin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 登出确认，文案沿用 v1。颜色与字取当前皮肤的 MaterialTheme 映射。 */
@Composable
fun LogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登出 tailnet？", style = MaterialTheme.typography.titleLarge) },
        text = { Text("登出后要在电脑浏览器里重新批准登录，期间代理用不了。", style = MaterialTheme.typography.bodyLarge) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", style = MaterialTheme.typography.labelLarge, color = cs.primary) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("登出", style = MaterialTheme.typography.labelLarge, color = cs.error) } },
    )
}

/** 打包进 App 的第三方组件与许可证，开源许可对话框列它们。 */
val OPEN_SOURCE_LICENSES: List<Pair<String, String>> = listOf(
    "MetaCubeX meta-rules-dat（国内域名快照）" to "GPL-3.0 · 来源与筛选方式见 core/ptcore/assets/README.md",
    "Petrel（本应用）" to "GPL-3.0-or-later",
    "mihomo（MetaCubeX）" to "GPL-3.0",
    "tailscale / tsnet（metacubex fork）" to "BSD-3-Clause",
    "Rubik" to "SIL OFL 1.1",
    "Oswald" to "SIL OFL 1.1",
    "IBM Plex Mono" to "SIL OFL 1.1",
    "Material Symbols" to "Apache-2.0",
)

/** 开源许可：列出本应用与打包的字体、图标、mihomo、tailscale 及各自许可证。GPL 全文在源码仓库根目录的 `LICENSE`，其余在 `licenses/`。 */
@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开源许可", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                OPEN_SOURCE_LICENSES.forEach { (name, license) ->
                    Column(Modifier.padding(bottom = 10.dp)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                        Text(license, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                Text("许可证全文在源码仓库的 licenses/ 目录。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", style = MaterialTheme.typography.labelLarge, color = cs.primary) } },
    )
}
