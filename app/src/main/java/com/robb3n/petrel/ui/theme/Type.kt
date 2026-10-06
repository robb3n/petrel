package com.robb3n.petrel.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.robb3n.petrel.R

/** 打包的 IBM Plex Mono：400 / 500。只有 `.lbl em`（画稿的 `--mono`）与登录链接、导入错误框用它（spec §2.5）。 */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)
