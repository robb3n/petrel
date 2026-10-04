package com.robb3n.petrel.ui.skin.night

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robb3n.petrel.R
import com.robb3n.petrel.ui.theme.PlexMono

/**
 * 夜航的颜色 token。视觉真相源是画稿 `docs/spec/assets/ui-skins/src/NightHome.dc.html` 的 `.phone`（默认面 = 深）与
 * `.phone[data-theme="light"]`（浅）CSS 变量，名字同名（`--p1` → [NightColors.p1]），数值逐一照抄。
 * 夜航的「深」是画稿默认面，所以 [NightDark] 才是 `.phone` 的基础规则。
 */
@Immutable
data class NightColors(
    val isDark: Boolean,
    val bg: Color,
    val p1: Color,
    val p2: Color,
    val line: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val ok: Color,
    val okBg: Color,
    val warn: Color,
    val warnBg: Color,
    val err: Color,
    /** 电源键外圈的光晕（`--glow`，只有连接页用）。 */
    val glow: Color,
)

val NightDark = NightColors(
    isDark = true,
    bg = Color(0xFF0A0F17),
    p1 = Color(0xFF0F1722),
    p2 = Color(0xFF162130),
    line = Color(0xFF213045),
    ink = Color(0xFFE6EDF5),
    ink2 = Color(0xFF97A6BA),
    ink3 = Color(0xFF72839A),
    ok = Color(0xFF4ADE9A),
    okBg = Color(0x1F4ADE9A), // .12
    warn = Color(0xFFF4B34C),
    warnBg = Color(0x21F4B34C), // .13
    err = Color(0xFFFF7B6E),
    glow = Color(0x1A4ADE9A), // .10
)

val NightLight = NightColors(
    isDark = false,
    bg = Color(0xFFE8EDF2),
    p1 = Color(0xFFFFFFFF),
    p2 = Color(0xFFEEF2F7),
    line = Color(0xFFD3DCE6),
    ink = Color(0xFF0B1524),
    ink2 = Color(0xFF46566C),
    ink3 = Color(0xFF66768C),
    ok = Color(0xFF0B7A49),
    okBg = Color(0x1A0B7A49), // .10
    warn = Color(0xFF985600),
    warnBg = Color(0x1A985600), // .10
    err = Color(0xFFB42E22),
    glow = Color(0x120B7A49), // .07
)

/** 界面字（`var(--ui)`）= IBM Plex Sans 400 / 500 / 600，静态 TTF。中文回落系统字体。 */
val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

/** 展示字（`var(--disp)`）= Barlow Condensed 500 / 600：品牌字、页标题、读数。 */
val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
)

private val LocalNightColors = staticCompositionLocalOf { NightDark }

/** 取当前夜航 token：`Night.colors.ok`。 */
object Night {
    val colors: NightColors
        @Composable @ReadOnlyComposable get() = LocalNightColors.current
}

private fun nightColorScheme(c: NightColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.ok, onPrimary = c.bg,
        primaryContainer = c.okBg, onPrimaryContainer = c.ok,
        secondary = c.ink2, onSecondary = c.bg,
        secondaryContainer = c.p2, onSecondaryContainer = c.ink,
        tertiary = c.warn, onTertiary = c.bg,
        tertiaryContainer = c.warnBg, onTertiaryContainer = c.warn,
        background = c.bg, onBackground = c.ink,
        surface = c.p1, onSurface = c.ink,
        surfaceVariant = c.p2, onSurfaceVariant = c.ink2,
        surfaceTint = Color.Transparent,
        inverseSurface = c.ink, inverseOnSurface = c.bg,
        error = c.err, onError = c.bg,
        errorContainer = c.p2, onErrorContainer = c.err,
        outline = c.ink3, outlineVariant = c.line,
        scrim = Color.Black,
        surfaceBright = c.p2, surfaceDim = c.bg,
        surfaceContainerLowest = c.bg, surfaceContainerLow = c.p1,
        surfaceContainer = c.p1, surfaceContainerHigh = c.p1, surfaceContainerHighest = c.p2,
    )
} else {
    lightColorScheme(
        primary = c.ok, onPrimary = c.p1,
        primaryContainer = c.okBg, onPrimaryContainer = c.ok,
        secondary = c.ink2, onSecondary = c.p1,
        secondaryContainer = c.p2, onSecondaryContainer = c.ink,
        tertiary = c.warn, onTertiary = c.p1,
        tertiaryContainer = c.warnBg, onTertiaryContainer = c.warn,
        background = c.bg, onBackground = c.ink,
        surface = c.p1, onSurface = c.ink,
        surfaceVariant = c.p2, onSurfaceVariant = c.ink2,
        surfaceTint = Color.Transparent,
        inverseSurface = c.ink, inverseOnSurface = c.bg,
        error = c.err, onError = c.p1,
        errorContainer = c.p2, onErrorContainer = c.err,
        outline = c.ink3, outlineVariant = c.line,
        scrim = Color.Black,
        surfaceBright = c.p1, surfaceDim = c.bg,
        surfaceContainerLowest = c.p1, surfaceContainerLow = c.p1,
        surfaceContainer = c.p1, surfaceContainerHigh = c.p1, surfaceContainerHighest = c.p2,
    )
}

/** 没重画的 M3 组件（AlertDialog 等）经 MaterialTheme 取夜航的色与字；字号落在画稿的常用档位上。 */
private fun nightTypography(): Typography {
    fun sans(weight: FontWeight, size: Float, line: Float) =
        TextStyle(fontFamily = PlexSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)
    return Typography(
        headlineSmall = sans(FontWeight.SemiBold, 22f, 28f),
        titleLarge = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 28.sp),
        titleMedium = sans(FontWeight.SemiBold, 15f, 20f),
        titleSmall = sans(FontWeight.Medium, 14f, 18f),
        bodyLarge = sans(FontWeight.Normal, 14f, 20f),
        bodyMedium = sans(FontWeight.Normal, 13f, 18f),
        bodySmall = sans(FontWeight.Normal, 11.5f, 16f),
        labelLarge = sans(FontWeight.Medium, 13f, 16f),
        labelMedium = sans(FontWeight.Medium, 11.5f, 14f),
        labelSmall = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp),
    )
}

@Composable
fun NightTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) NightDark else NightLight
    CompositionLocalProvider(LocalNightColors provides colors) {
        MaterialTheme(
            colorScheme = nightColorScheme(colors),
            typography = nightTypography(),
            // 夜航的圆角是 14（panel）/ 8–10（按钮）：对话框跟 panel
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(10.dp),
                large = RoundedCornerShape(14.dp),
                extraLarge = RoundedCornerShape(14.dp),
            ),
            content = content,
        )
    }
}
