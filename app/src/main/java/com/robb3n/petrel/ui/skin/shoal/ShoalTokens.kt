package com.robb3n.petrel.ui.skin.shoal

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

/**
 * Shoal 的颜色 token。视觉真相源是画稿 `docs/spec/assets/ui-skins/src/Main.dc.html` 的 `.phone`（浅）与
 * `.phone[data-theme="dark"]`（深）CSS 变量，名字同名（`--wall` → [ShoalColors.wall]），数值逐一照抄。
 */
@Immutable
data class ShoalColors(
    val isDark: Boolean,
    val wall: Color,
    val sf: Color,
    val sf2: Color,
    val sf3: Color,
    val tx: Color,
    val tx2: Color,
    val hair: Color,
    val em: Color,
    val ol: Color,
    val onol: Color,
    val gold: Color,
    val butter: Color,
    val butterInk: Color,
    val brown: Color,
    val brownInk: Color,
    val sage: Color,
    val sageInk: Color,
    val mint: Color,
    val mintInk: Color,
    val red: Color,
    val redInk: Color,
    val pink: Color,
    val pinkInk: Color,
    val knob: Color,
    val heroA: Color,
    val heroB: Color,
    val heroC: Color,
    val s0: Color,
    val s1: Color,
    val s2: Color,
    val s3: Color,
    /** 红色作前景（错误行图标 / 文字）：浅色 = `--red`，深色用更亮的 #E0787C，免得压在深底上看不清。 */
    val redFg: Color,
    /** `.tag.live::before` 的在线点。 */
    val liveDot: Color,
    /** `--shIsle` / `--shPill` 的近似：阴影主色与 0.5dp 外描边色。 */
    val shadow: Color,
    val isleRing: Color,
    val pillRing: Color,
)

val ShoalLight = ShoalColors(
    isDark = false,
    wall = Color(0xFFE9E1D3),
    sf = Color(0xFFFEF6EE),
    sf2 = Color(0xFFF6ECDF),
    sf3 = Color(0xFFECE0CF),
    tx = Color(0xFF2A2217),
    tx2 = Color(0xFF8A7C65),
    hair = Color(0xFFEADFCF),
    em = Color(0xFFB1A48E),
    ol = Color(0xFF705A0F),
    onol = Color(0xFFFFF6E0),
    gold = Color(0xFFD4B35B),
    butter = Color(0xFFF6DE96),
    butterInk = Color(0xFF2A1800),
    brown = Color(0xFF665A42),
    brownInk = Color(0xFFF2E6CF),
    sage = Color(0xFF4E604E),
    sageInk = Color(0xFFDCEADA),
    mint = Color(0xFFCCEAC6),
    mintInk = Color(0xFF1F3B22),
    red = Color(0xFFB4181E),
    redInk = Color(0xFFFFF1EC),
    pink = Color(0xFFF4C9C4),
    pinkInk = Color(0xFF6B0F12),
    knob = Color(0xFFB7AA95),
    heroA = Color(0xFF705A0F),
    heroB = Color(0xFF8F7420),
    heroC = Color(0xFFC9A64C),
    s0 = Color(0xFF705A0F),
    s1 = Color(0xFFB4181E),
    s2 = Color(0xFF8A6A12),
    s3 = Color(0xFF4E604E),
    redFg = Color(0xFFB4181E),
    liveDot = Color(0xFF4F9A5B),
    // rgba(70,48,18,…)：投影色取不透明原色，实际浓度由平台 spot / ambient alpha 决定
    shadow = Color(0xFF463012),
    isleRing = Color(0x14463012),
    pillRing = Color(0x12463012),
)

val ShoalDark = ShoalColors(
    isDark = true,
    wall = Color(0xFF14120E),
    sf = Color(0xFF221E18),
    sf2 = Color(0xFF2B261E),
    sf3 = Color(0xFF383126),
    tx = Color(0xFFEDE4D6),
    tx2 = Color(0xFFA3977F),
    hair = Color(0xFF302A21),
    em = Color(0xFF7C715F),
    ol = Color(0xFFD4B35B),
    onol = Color(0xFF2A1800),
    gold = Color(0xFFD4B35B),
    butter = Color(0xFF4A3E1E),
    butterInk = Color(0xFFF6DE96),
    brown = Color(0xFF6B5E45),
    brownInk = Color(0xFFF2E6CF),
    sage = Color(0xFF4E604E),
    sageInk = Color(0xFFDCEADA),
    mint = Color(0xFF2C4230),
    mintInk = Color(0xFFCCEAC6),
    red = Color(0xFF9E2A2E),
    redInk = Color(0xFFFFF1EC),
    pink = Color(0xFF4A2422),
    pinkInk = Color(0xFFF4C9C4),
    knob = Color(0xFF7C715F),
    heroA = Color(0xFF3B300F),
    heroB = Color(0xFF5E4E14),
    heroC = Color(0xFF8F7420),
    s0 = Color(0xFFD4B35B),
    s1 = Color(0xFFE0787C),
    s2 = Color(0xFFC9B48A),
    s3 = Color(0xFF9DBB9D),
    redFg = Color(0xFFE0787C),
    liveDot = Color(0xFF4F9A5B),
    shadow = Color(0xFF000000),
    // rgba(255,240,210,.07)
    isleRing = Color(0x12FFF0D2),
    pillRing = Color(0x12FFF0D2),
)

/**
 * 界面字（`var(--ui)`）= Rubik，静态 TTF。Medium / SemiBold / Bold 三档是按系统中文笔画粗细切的 wght 430 / 470 / 510
 * 实例（来源 Mu3ic `scripts/cut-rubik.py`），文件自报的字重仍是 500 / 600 / 700。中文回落系统字体。
 */
val Rubik = FontFamily(
    Font(R.font.rubik_regular, FontWeight.Normal),
    Font(R.font.rubik_medium, FontWeight.Medium),
    Font(R.font.rubik_semibold, FontWeight.SemiBold),
    Font(R.font.rubik_bold, FontWeight.Bold),
)

/** 数字 / 展示字（`var(--disp)`）= Oswald。 */
val Oswald = FontFamily(
    Font(R.font.oswald_regular, FontWeight.Normal),
    Font(R.font.oswald_medium, FontWeight.Medium),
)

private val LocalShoalColors = staticCompositionLocalOf { ShoalLight }

/** 取当前 Shoal token：`Shoal.colors.ol`。 */
object Shoal {
    val colors: ShoalColors
        @Composable @ReadOnlyComposable get() = LocalShoalColors.current
}

private fun shoalColorScheme(c: ShoalColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.ol, onPrimary = c.onol,
        primaryContainer = c.butter, onPrimaryContainer = c.butterInk,
        inversePrimary = c.gold,
        secondary = c.brown, onSecondary = c.brownInk,
        secondaryContainer = c.butter, onSecondaryContainer = c.butterInk,
        tertiary = c.sage, onTertiary = c.sageInk,
        tertiaryContainer = c.mint, onTertiaryContainer = c.mintInk,
        background = c.wall, onBackground = c.tx,
        surface = c.sf, onSurface = c.tx,
        surfaceVariant = c.sf2, onSurfaceVariant = c.tx2,
        surfaceTint = Color.Transparent,
        inverseSurface = c.tx, inverseOnSurface = c.sf,
        error = c.redFg, onError = c.redInk,
        errorContainer = c.pink, onErrorContainer = c.pinkInk,
        outline = c.em, outlineVariant = c.hair,
        scrim = Color.Black,
        surfaceBright = c.sf2, surfaceDim = c.wall,
        surfaceContainerLowest = c.sf, surfaceContainerLow = c.sf,
        surfaceContainer = c.sf, surfaceContainerHigh = c.sf2, surfaceContainerHighest = c.sf3,
    )
} else {
    lightColorScheme(
        primary = c.ol, onPrimary = c.onol,
        primaryContainer = c.butter, onPrimaryContainer = c.butterInk,
        inversePrimary = c.gold,
        secondary = c.brown, onSecondary = c.brownInk,
        secondaryContainer = c.butter, onSecondaryContainer = c.butterInk,
        tertiary = c.sage, onTertiary = c.sageInk,
        tertiaryContainer = c.mint, onTertiaryContainer = c.mintInk,
        background = c.wall, onBackground = c.tx,
        surface = c.sf, onSurface = c.tx,
        surfaceVariant = c.sf2, onSurfaceVariant = c.tx2,
        surfaceTint = Color.Transparent,
        inverseSurface = c.tx, inverseOnSurface = c.sf,
        error = c.red, onError = c.redInk,
        errorContainer = c.pink, onErrorContainer = c.pinkInk,
        outline = c.em, outlineVariant = c.hair,
        scrim = Color.Black,
        surfaceBright = c.sf, surfaceDim = c.wall,
        surfaceContainerLowest = c.sf, surfaceContainerLow = c.sf,
        surfaceContainer = c.sf, surfaceContainerHigh = c.sf2, surfaceContainerHighest = c.sf3,
    )
}

/** 没重画的 M3 组件（AlertDialog 等）经 MaterialTheme 取 Shoal 的色与字；字号落在画稿的常用档位上。 */
private fun shoalTypography(): Typography {
    fun rubik(weight: FontWeight, size: Float, line: Float) =
        TextStyle(fontFamily = Rubik, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)
    return Typography(
        headlineSmall = rubik(FontWeight.SemiBold, 22f, 28f),
        titleLarge = rubik(FontWeight.SemiBold, 20f, 24f),
        titleMedium = rubik(FontWeight.SemiBold, 15f, 19f),
        titleSmall = rubik(FontWeight.SemiBold, 13.5f, 18f),
        bodyLarge = rubik(FontWeight.Normal, 14.5f, 20f),
        bodyMedium = rubik(FontWeight.Normal, 13.5f, 18f),
        bodySmall = rubik(FontWeight.Normal, 11.5f, 15.5f),
        labelLarge = rubik(FontWeight.Medium, 12.5f, 16f),
        labelMedium = rubik(FontWeight.Medium, 12f, 15f),
        labelSmall = rubik(FontWeight.Medium, 10.5f, 14f),
    )
}

@Composable
fun ShoalTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) ShoalDark else ShoalLight
    CompositionLocalProvider(LocalShoalColors provides colors) {
        MaterialTheme(
            colorScheme = shoalColorScheme(colors),
            typography = shoalTypography(),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(9.dp),
                small = RoundedCornerShape(14.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(22.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
            content = content,
        )
    }
}
