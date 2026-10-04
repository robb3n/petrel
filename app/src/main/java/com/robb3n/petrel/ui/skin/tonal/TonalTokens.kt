package com.robb3n.petrel.ui.skin.tonal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tonal 的颜色 token（Material 3 固定蓝色调色板，不用动态取色，spec 决策 4）。视觉真相源是画稿
 * `docs/spec/assets/ui-skins/src/Tonal{Home,Nodes,Tailnet}.dc.html` 的 `.phone`（浅）与 `.phone[data-theme="dark"]`（深）CSS 变量，
 * 名字同名（`--priC` → [TonalColors.priC]），数值逐一照抄。`errC` / `onErrC` 是 spec §2.7 给的 error container 色（画稿没画偏高 band）。
 */
@Immutable
data class TonalColors(
    val isDark: Boolean,
    val pri: Color,
    val onPri: Color,
    val priC: Color,
    val onPriC: Color,
    val secC: Color,
    val onSecC: Color,
    val terC: Color,
    val onTerC: Color,
    val sf: Color,
    val sfC: Color,
    val sfH: Color,
    val sfHH: Color,
    val on: Color,
    val onV: Color,
    val outV: Color,
    val okC: Color,
    val onOkC: Color,
    val warnC: Color,
    val onWarnC: Color,
    /** tailnet 页色调卡里 chip 的底：`--chipBg`。 */
    val chipBg: Color,
    val errC: Color,
    val onErrC: Color,
    /** 红色作前景（错误文字）：M3 `error`，画稿没有，取标准基线色。 */
    val err: Color,
    val onErr: Color,
)

val TonalLight = TonalColors(
    isDark = false,
    pri = Color(0xFF415F91),
    onPri = Color(0xFFFFFFFF),
    priC = Color(0xFFD6E3FF),
    onPriC = Color(0xFF001B3E),
    secC = Color(0xFFDAE2F9),
    onSecC = Color(0xFF131C2B),
    terC = Color(0xFFFAD8FD),
    onTerC = Color(0xFF28132E),
    sf = Color(0xFFF9F9FF),
    sfC = Color(0xFFEDEDF4),
    sfH = Color(0xFFE7E8EE),
    sfHH = Color(0xFFE2E2E9),
    on = Color(0xFF191C20),
    onV = Color(0xFF44474E),
    outV = Color(0xFFC4C6D0),
    okC = Color(0xFFC2EFCB),
    onOkC = Color(0xFF0A3A1E),
    warnC = Color(0xFFFFDEA6),
    onWarnC = Color(0xFF3A2800),
    chipBg = Color(0x99FFFFFF),
    errC = Color(0xFFFFDAD6),
    onErrC = Color(0xFF410002),
    err = Color(0xFFBA1A1A),
    onErr = Color(0xFFFFFFFF),
)

val TonalDark = TonalColors(
    isDark = true,
    pri = Color(0xFFAAC7FF),
    onPri = Color(0xFF0A305F),
    priC = Color(0xFF284777),
    onPriC = Color(0xFFD6E3FF),
    secC = Color(0xFF3E4759),
    onSecC = Color(0xFFDAE2F9),
    terC = Color(0xFF573E5C),
    onTerC = Color(0xFFFAD8FD),
    sf = Color(0xFF111318),
    sfC = Color(0xFF1D2024),
    sfH = Color(0xFF282A2F),
    sfHH = Color(0xFF33353A),
    on = Color(0xFFE2E2E9),
    onV = Color(0xFFC4C6D0),
    outV = Color(0xFF44474E),
    okC = Color(0xFF1E5132),
    onOkC = Color(0xFFBDEFC9),
    warnC = Color(0xFF5C4300),
    onWarnC = Color(0xFFFFDEA6),
    chipBg = Color(0x38000000),
    errC = Color(0xFF93000A),
    onErrC = Color(0xFFFFDAD6),
    err = Color(0xFFFFB4AB),
    onErr = Color(0xFF690005),
)

private val LocalTonalColors = staticCompositionLocalOf { TonalLight }

/** 取当前 Tonal token：`Tonal.colors.pri`。 */
object Tonal {
    val colors: TonalColors
        @Composable @ReadOnlyComposable get() = LocalTonalColors.current
}

private fun tonalColorScheme(c: TonalColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.pri, onPrimary = c.onPri,
        primaryContainer = c.priC, onPrimaryContainer = c.onPriC,
        secondary = c.onSecC, onSecondary = c.secC,
        secondaryContainer = c.secC, onSecondaryContainer = c.onSecC,
        tertiary = c.onTerC, onTertiary = c.terC,
        tertiaryContainer = c.terC, onTertiaryContainer = c.onTerC,
        background = c.sf, onBackground = c.on,
        surface = c.sf, onSurface = c.on,
        surfaceVariant = c.sfHH, onSurfaceVariant = c.onV,
        surfaceTint = Color.Transparent,
        inverseSurface = c.on, inverseOnSurface = c.sf,
        inversePrimary = c.priC,
        error = c.err, onError = c.onErr,
        errorContainer = c.errC, onErrorContainer = c.onErrC,
        outline = c.onV, outlineVariant = c.outV,
        scrim = Color.Black,
        surfaceBright = c.sfHH, surfaceDim = c.sf,
        surfaceContainerLowest = c.sf, surfaceContainerLow = c.sfC,
        surfaceContainer = c.sfC, surfaceContainerHigh = c.sfH, surfaceContainerHighest = c.sfHH,
    )
} else {
    lightColorScheme(
        primary = c.pri, onPrimary = c.onPri,
        primaryContainer = c.priC, onPrimaryContainer = c.onPriC,
        secondary = c.onSecC, onSecondary = c.secC,
        secondaryContainer = c.secC, onSecondaryContainer = c.onSecC,
        tertiary = c.onTerC, onTertiary = c.terC,
        tertiaryContainer = c.terC, onTertiaryContainer = c.onTerC,
        background = c.sf, onBackground = c.on,
        surface = c.sf, onSurface = c.on,
        surfaceVariant = c.sfHH, onSurfaceVariant = c.onV,
        surfaceTint = Color.Transparent,
        inverseSurface = c.on, inverseOnSurface = c.sf,
        inversePrimary = c.priC,
        error = c.err, onError = c.onErr,
        errorContainer = c.errC, onErrorContainer = c.onErrC,
        outline = c.onV, outlineVariant = c.outV,
        scrim = Color.Black,
        surfaceBright = c.sf, surfaceDim = c.sfHH,
        surfaceContainerLowest = c.sf, surfaceContainerLow = c.sfC,
        surfaceContainer = c.sfC, surfaceContainerHigh = c.sfH, surfaceContainerHighest = c.sfHH,
    )
}

/** 没重画的 M3 组件（AlertDialog 等）经 MaterialTheme 取 Tonal 的色；字用 M3 默认 Typography（系统字体），圆角取 M3 标准档。 */
@Composable
fun TonalTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) TonalDark else TonalLight
    CompositionLocalProvider(LocalTonalColors provides colors) {
        MaterialTheme(
            colorScheme = tonalColorScheme(colors),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
