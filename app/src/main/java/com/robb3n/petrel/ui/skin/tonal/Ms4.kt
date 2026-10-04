package com.robb3n.petrel.ui.skin.tonal

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.robb3n.petrel.R

/**
 * Material Symbols Rounded 图标（Tonal，wght 400）：`res/drawable/ms400_<name>.xml` = FILL 0，`ms400_<name>_fill.xml` = FILL 1，
 * 取自 GitHub `google/material-design-icons` 的 `symbols/android/<name>/materialsymbolsrounded/` 里的默认（wght 400）文件
 * `<name>_24px.xml` 与 `<name>_fill1_24px.xml`（Apache-2.0），去掉了 `android:tint` 属性（着色一律由 `Icon(tint = …)` 决定）。
 * 与 Shoal 的 wght 500 组（`Ms`，前缀 `ms_`）分开，不混用（spec §2.6）。画稿里 `class="ms f"` 用 `…Fill` 版本。
 */
object Ms4 {
    val arrowBack: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_arrow_back)
    val check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_check)
    val checkCircleFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_check_circle_fill)
    val chevronRight: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_chevron_right)
    val contentCopy: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_content_copy)
    val description: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_description)
    val desktopWindows: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_desktop_windows)
    val dns: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_dns)
    val download: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_download)
    val errorFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_error_fill)
    val hub: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_hub)
    val hubFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_hub_fill)
    val lan: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_lan)
    val laptopMac: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_laptop_mac)
    val login: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_login)
    val logout: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_logout)
    val openInNew: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_open_in_new)
    val powerSettingsNew: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_power_settings_new)
    val powerSettingsNewFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_power_settings_new_fill)
    val public: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_public)
    val publicFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_public_fill)
    val radioButtonChecked: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_radio_button_checked)
    val radioButtonUnchecked: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_radio_button_unchecked)
    val refresh: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_refresh)
    val route: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_route)
    val routeFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_route_fill)
    val settings: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_settings)
    val smartphone: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_smartphone)
    val smartphoneFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_smartphone_fill)
    val swapHoriz: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_swap_horiz)
    val timer: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms400_timer)
}
